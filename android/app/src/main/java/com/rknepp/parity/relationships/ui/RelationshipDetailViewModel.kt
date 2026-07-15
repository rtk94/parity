package com.rknepp.parity.relationships.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.rknepp.parity.ServiceLocator
import com.rknepp.parity.home.data.MeRepository
import com.rknepp.parity.ledger.data.LedgerRepository
import com.rknepp.parity.ledger.data.dto.AttachmentDto
import com.rknepp.parity.ledger.data.dto.CommentDto
import com.rknepp.parity.network.ApiResult
import com.rknepp.parity.relationships.data.RelationshipRepository
import com.rknepp.parity.relationships.data.dto.BalanceViewDto
import com.rknepp.parity.ui.components.formatIsoDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * A balance rendered from the caller's point of view. [settled] means
 * net is zero. Otherwise exactly one of the owe directions is set.
 */
data class BalanceLine(
    val settled: Boolean,
    val youOwe: Boolean,
    val amountText: String,
    val counterpartyName: String,
)

/** A ledger-entry comment resolved for display. */
data class CommentRow(
    val id: Long,
    val authorName: String,
    val isMine: Boolean,
    val dateText: String,
    val content: String,
)

/** An expense attachment (receipt) resolved for display. */
data class AttachmentRow(
    val id: Long,
    val filename: String,
    val sizeText: String,
    val contentType: String,
    /** Uploaded by the current user — controls delete visibility. */
    val isMine: Boolean,
    val isDownloading: Boolean = false,
)

/**
 * A downloaded attachment handed to the UI to open in the system viewer.
 * One-shot: the screen writes the bytes to cache, fires the view intent,
 * then calls [RelationshipDetailViewModel.consumePendingOpen].
 */
data class OpenableAttachment(
    val filename: String,
    val contentType: String,
    val bytes: ByteArray,
) {
    // ByteArray needs value-based equals/hashCode for StateFlow dedup.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OpenableAttachment) return false
        return filename == other.filename &&
            contentType == other.contentType &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = filename.hashCode()
        result = 31 * result + contentType.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

data class LedgerRow(
    val id: Long,
    val type: String, // "expense" or "payment"
    val description: String,
    val category: String?,
    val amountText: String,
    val status: String,
    val timestamp: String,
    val dateText: String,
    /** One-line explanation, e.g. "You paid · Bob owes 6.17 USD". */
    val subtitle: String,
    /**
     * Signed effect on what the counterparty owes you, in cents.
     * Positive: this entry moves money your way. Null for entries with
     * no balance effect (discarded) or reversals shown for history.
     */
    val deltaCents: Long?,
    val isPayer: Boolean, // For expense: did I pay? For payment: did I send?
    val isReversal: Boolean,
    val canConfirm: Boolean,
    val canDiscard: Boolean,
    val canReverse: Boolean,
    /**
     * A confirmed reversal of this entry, nested here as a history detail
     * line instead of floating as its own row. Null when not reversed.
     */
    val reversalItem: LedgerRow? = null,
    val comments: List<CommentRow>? = null,
    val isLoadingComments: Boolean = false,
    val commentDraft: String = "",
    val isPostingComment: Boolean = false,
    // Attachments (expense-only). Null when the section is collapsed /
    // not yet loaded; an empty list means loaded-but-none.
    val attachments: List<AttachmentRow>? = null,
    val isLoadingAttachments: Boolean = false,
    val isUploadingAttachment: Boolean = false,
)

data class RelationshipDetailData(
    val counterpartyName: String,
    val counterpartyUsername: String,
    val status: String,
    val currencyCode: String,
    val confirmed: BalanceLine,
    val projected: BalanceLine,
    val canAccept: Boolean,
    val canReject: Boolean,
    val ledgerItems: List<LedgerRow>,
)

sealed interface RelationshipDetailState {
    data object Loading : RelationshipDetailState
    data class Loaded(val data: RelationshipDetailData) : RelationshipDetailState
    data object Error : RelationshipDetailState
}

class RelationshipDetailViewModel(
    private val relationshipId: Long,
    private val relationshipRepository: RelationshipRepository,
    private val ledgerRepository: LedgerRepository,
    private val meRepository: MeRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<RelationshipDetailState>(RelationshipDetailState.Loading)
    val state: StateFlow<RelationshipDetailState> = _state.asStateFlow()

    /** One-shot user-facing error for entry actions, shown in a snackbar. */
    private val _actionError = MutableStateFlow<String?>(null)
    val actionError: StateFlow<String?> = _actionError.asStateFlow()

    /** One-shot: a downloaded attachment for the UI to open in a viewer. */
    private val _pendingOpen = MutableStateFlow<OpenableAttachment?>(null)
    val pendingOpen: StateFlow<OpenableAttachment?> = _pendingOpen.asStateFlow()

    private var myId: Long = -1
    private var loadInFlight = false

    init { reload() }

    /** Full load: drops to the Loading state first. */
    fun reload() = load(showLoading = true)

    /** Silent revalidation that keeps current content on screen. */
    fun refresh() = load(showLoading = _state.value !is RelationshipDetailState.Loaded)

    private fun load(showLoading: Boolean) {
        if (loadInFlight) return
        loadInFlight = true
        if (showLoading) _state.update { RelationshipDetailState.Loading }
        viewModelScope.launch {
            try {
                val meResult = meRepository.fetchMe()
                if (meResult !is ApiResult.Success) {
                    _state.update { RelationshipDetailState.Error }
                    return@launch
                }
                myId = meResult.data.id

                val relResult = relationshipRepository.get(relationshipId)
                if (relResult !is ApiResult.Success) {
                    _state.update { RelationshipDetailState.Error }
                    return@launch
                }
                val rel = relResult.data

                val balResult = relationshipRepository.balance(relationshipId)
                if (balResult !is ApiResult.Success) {
                    _state.update { RelationshipDetailState.Error }
                    return@launch
                }
                val bal = balResult.data

                val expensesResult = ledgerRepository.listExpenses(relationshipId)
                val paymentsResult = ledgerRepository.listPayments(relationshipId)

                val expenses =
                    if (expensesResult is ApiResult.Success) expensesResult.data.items
                    else emptyList()
                val payments =
                    if (paymentsResult is ApiResult.Success) paymentsResult.data.items
                    else emptyList()

                val other = if (rel.invitingUser.id == myId) rel.invitedUser else rel.invitingUser
                val otherName = other.displayName

                val ledgerRows = mutableListOf<LedgerRow>()
                for (e in expenses) {
                    // A confirmed reversal is nested under its original below,
                    // not floated as its own top-level row.
                    if (e.reverses_expense_id != null && e.status == "confirmed") continue
                    val isPayer = e.payer_user_id == myId
                    val otherShare = e.shares
                        .firstOrNull { it.user_id != e.payer_user_id }
                        ?.amount_cents ?: 0L
                    val shareText = formatCents(otherShare, rel.currencyCode)
                    val subtitle = if (isPayer) {
                        "You paid · $otherName owes $shareText"
                    } else {
                        "$otherName paid · you owe $shareText"
                    }
                    val hasEffect = e.status == "pending" || e.status == "confirmed"
                    val reversal = expenses.firstOrNull {
                        it.reverses_expense_id == e.id && it.status == "confirmed"
                    }
                    ledgerRows.add(
                        LedgerRow(
                            id = e.id,
                            type = "expense",
                            description = e.description,
                            category = e.category,
                            amountText = formatCents(e.total_cents, rel.currencyCode),
                            status = e.status,
                            timestamp = e.created_at,
                            dateText = formatIsoDate(e.created_at),
                            subtitle = subtitle,
                            deltaCents = if (hasEffect) {
                                if (isPayer) otherShare else -otherShare
                            } else {
                                null
                            },
                            isPayer = isPayer,
                            isReversal = e.reverses_expense_id != null,
                            canConfirm = e.status == "pending" && e.created_by_user_id != myId,
                            canDiscard = e.status == "pending",
                            canReverse = e.status == "confirmed" &&
                                e.reverses_expense_id == null && reversal == null,
                            reversalItem = reversal?.let { rev ->
                                LedgerRow(
                                    id = rev.id,
                                    type = "expense",
                                    description = rev.description,
                                    category = rev.category,
                                    amountText = formatCents(rev.total_cents, rel.currencyCode),
                                    status = rev.status,
                                    timestamp = rev.created_at,
                                    dateText = formatIsoDate(rev.created_at),
                                    subtitle = "Reversal",
                                    deltaCents = null,
                                    isPayer = rev.payer_user_id == myId,
                                    isReversal = true,
                                    canConfirm = false,
                                    canDiscard = false,
                                    canReverse = false,
                                )
                            },
                        ),
                    )
                }
                for (p in payments) {
                    if (p.reverses_payment_id != null && p.status == "confirmed") continue
                    val isSender = p.from_user_id == myId
                    val subtitle = if (isSender) "You paid $otherName" else "$otherName paid you"
                    val hasEffect = p.status == "pending" || p.status == "confirmed"
                    val reversal = payments.firstOrNull {
                        it.reverses_payment_id == p.id && it.status == "confirmed"
                    }
                    ledgerRows.add(
                        LedgerRow(
                            id = p.id,
                            type = "payment",
                            description = p.description,
                            category = null,
                            amountText = formatCents(p.amount_cents, rel.currencyCode),
                            status = p.status,
                            timestamp = p.created_at,
                            dateText = formatIsoDate(p.created_at),
                            subtitle = subtitle,
                            deltaCents = if (hasEffect) {
                                if (isSender) p.amount_cents else -p.amount_cents
                            } else {
                                null
                            },
                            isPayer = isSender,
                            isReversal = p.reverses_payment_id != null,
                            canConfirm = p.status == "pending" && p.created_by_user_id != myId,
                            canDiscard = p.status == "pending",
                            canReverse = p.status == "confirmed" &&
                                p.reverses_payment_id == null && reversal == null,
                            reversalItem = reversal?.let { rev ->
                                LedgerRow(
                                    id = rev.id,
                                    type = "payment",
                                    description = rev.description,
                                    category = null,
                                    amountText = formatCents(rev.amount_cents, rel.currencyCode),
                                    status = rev.status,
                                    timestamp = rev.created_at,
                                    dateText = formatIsoDate(rev.created_at),
                                    subtitle = "Reversal",
                                    deltaCents = null,
                                    isPayer = rev.from_user_id == myId,
                                    isReversal = true,
                                    canConfirm = false,
                                    canDiscard = false,
                                    canReverse = false,
                                )
                            },
                        ),
                    )
                }
                ledgerRows.sortByDescending { it.timestamp }

                _state.update {
                    RelationshipDetailState.Loaded(
                        RelationshipDetailData(
                            counterpartyName = otherName,
                            counterpartyUsername = other.username,
                            status = rel.status,
                            currencyCode = rel.currencyCode,
                            confirmed = bal.confirmed.toLine(myId, rel.currencyCode, otherName),
                            projected = bal.projected.toLine(myId, rel.currencyCode, otherName),
                            canAccept = rel.status == "pending" && rel.invitedUser.id == myId,
                            canReject = rel.status == "pending",
                            ledgerItems = ledgerRows,
                        ),
                    )
                }
            } finally {
                loadInFlight = false
            }
        }
    }

    fun accept() = runAction("Could not accept the invitation. Try again.") {
        relationshipRepository.accept(relationshipId)
    }

    fun reject() = runAction("Could not decline the invitation. Try again.") {
        relationshipRepository.reject(relationshipId)
    }

    fun confirmExpense(id: Long) = runAction("Could not confirm the expense. Try again.") {
        ledgerRepository.confirmExpense(id)
    }

    fun discardExpense(id: Long) = runAction("Could not discard the expense. Try again.") {
        ledgerRepository.discardExpense(id)
    }

    fun reverseExpense(id: Long) = runAction("Could not reverse the expense. Try again.") {
        ledgerRepository.reverseExpense(id)
    }

    fun confirmPayment(id: Long) = runAction("Could not confirm the payment. Try again.") {
        ledgerRepository.confirmPayment(id)
    }

    fun discardPayment(id: Long) = runAction("Could not discard the payment. Try again.") {
        ledgerRepository.discardPayment(id)
    }

    fun reversePayment(id: Long) = runAction("Could not reverse the payment. Try again.") {
        ledgerRepository.reversePayment(id)
    }

    fun consumeActionError() {
        _actionError.update { null }
    }

    private fun runAction(errorMessage: String, action: suspend () -> ApiResult<*>) {
        viewModelScope.launch {
            when (action()) {
                is ApiResult.Success -> refresh()
                else -> _actionError.update { errorMessage }
            }
        }
    }

    fun toggleComments(id: Long, type: String) {
        val currentState = _state.value as? RelationshipDetailState.Loaded ?: return
        val item = currentState.data.ledgerItems.find { it.id == id && it.type == type } ?: return

        if (item.comments != null) {
            updateItem(id, type) { it.copy(comments = null) }
        } else {
            updateItem(id, type) { it.copy(isLoadingComments = true) }
            viewModelScope.launch {
                val res = if (type == "expense") {
                    ledgerRepository.listExpenseComments(id)
                } else {
                    ledgerRepository.listPaymentComments(id)
                }
                if (res is ApiResult.Success) {
                    val rows = res.data.items.map { it.toRow() }
                    updateItem(id, type) { it.copy(isLoadingComments = false, comments = rows) }
                } else {
                    updateItem(id, type) { it.copy(isLoadingComments = false) }
                    _actionError.update { "Could not load comments. Try again." }
                }
            }
        }
    }

    fun updateCommentDraft(id: Long, type: String, draft: String) {
        updateItem(id, type) { it.copy(commentDraft = draft) }
    }

    fun postComment(id: Long, type: String) {
        val currentState = _state.value as? RelationshipDetailState.Loaded ?: return
        val item = currentState.data.ledgerItems.find { it.id == id && it.type == type } ?: return
        val content = item.commentDraft.trim()
        if (content.isBlank() || item.isPostingComment) return

        updateItem(id, type) { it.copy(isPostingComment = true) }
        viewModelScope.launch {
            val res = if (type == "expense") {
                ledgerRepository.createExpenseComment(id, content)
            } else {
                ledgerRepository.createPaymentComment(id, content)
            }
            when (res) {
                is ApiResult.Success -> {
                    // Clear the draft only on success; append the new
                    // comment locally so it shows without a refetch.
                    updateItem(id, type) {
                        it.copy(
                            isPostingComment = false,
                            commentDraft = "",
                            comments = (it.comments ?: emptyList()) + res.data.toRow(),
                        )
                    }
                }
                else -> {
                    updateItem(id, type) { it.copy(isPostingComment = false) }
                    _actionError.update { "Could not post the comment. Try again." }
                }
            }
        }
    }

    private fun CommentDto.toRow(): CommentRow {
        val counterpartyName =
            (_state.value as? RelationshipDetailState.Loaded)?.data?.counterpartyName ?: ""
        val mine = user_id == myId
        return CommentRow(
            id = id,
            authorName = if (mine) "You" else counterpartyName,
            isMine = mine,
            dateText = formatIsoDate(created_at),
            content = content,
        )
    }

    // --- Attachments (expense-only) -------------------------------------

    fun toggleAttachments(expenseId: Long) {
        val loaded = _state.value as? RelationshipDetailState.Loaded ?: return
        val item = loaded.data.ledgerItems.find { it.id == expenseId && it.type == "expense" } ?: return

        if (item.attachments != null) {
            updateItem(expenseId, "expense") { it.copy(attachments = null) }
        } else {
            updateItem(expenseId, "expense") { it.copy(isLoadingAttachments = true) }
            viewModelScope.launch {
                when (val res = ledgerRepository.listAttachments(expenseId)) {
                    is ApiResult.Success -> {
                        val rows = res.data.items.map { it.toRow() }
                        updateItem(expenseId, "expense") {
                            it.copy(isLoadingAttachments = false, attachments = rows)
                        }
                    }
                    else -> {
                        updateItem(expenseId, "expense") { it.copy(isLoadingAttachments = false) }
                        _actionError.update { "Could not load attachments. Try again." }
                    }
                }
            }
        }
    }

    fun uploadAttachment(
        expenseId: Long,
        filename: String,
        contentType: String,
        bytes: ByteArray,
    ) {
        val loaded = _state.value as? RelationshipDetailState.Loaded ?: return
        val item = loaded.data.ledgerItems.find { it.id == expenseId && it.type == "expense" } ?: return
        if (item.isUploadingAttachment) return

        updateItem(expenseId, "expense") { it.copy(isUploadingAttachment = true) }
        viewModelScope.launch {
            when (val res = ledgerRepository.uploadAttachment(expenseId, filename, contentType, bytes)) {
                is ApiResult.Success -> updateItem(expenseId, "expense") {
                    it.copy(
                        isUploadingAttachment = false,
                        // Reveal the section and append the new row locally.
                        attachments = (it.attachments ?: emptyList()) + res.data.toRow(),
                    )
                }
                is ApiResult.HttpFailure -> {
                    updateItem(expenseId, "expense") { it.copy(isUploadingAttachment = false) }
                    _actionError.update {
                        res.error?.message ?: "Could not upload the attachment. Try again."
                    }
                }
                else -> {
                    updateItem(expenseId, "expense") { it.copy(isUploadingAttachment = false) }
                    _actionError.update { "Could not upload the attachment. Check your connection." }
                }
            }
        }
    }

    fun openAttachment(expenseId: Long, attachment: AttachmentRow) {
        if (attachment.isDownloading) return
        updateAttachment(expenseId, attachment.id) { it.copy(isDownloading = true) }
        viewModelScope.launch {
            when (val res = ledgerRepository.downloadAttachment(attachment.id)) {
                is ApiResult.Success -> {
                    updateAttachment(expenseId, attachment.id) { it.copy(isDownloading = false) }
                    _pendingOpen.update {
                        OpenableAttachment(attachment.filename, attachment.contentType, res.data)
                    }
                }
                else -> {
                    updateAttachment(expenseId, attachment.id) { it.copy(isDownloading = false) }
                    _actionError.update { "Could not open the attachment. Try again." }
                }
            }
        }
    }

    fun deleteAttachment(expenseId: Long, attachmentId: Long) {
        viewModelScope.launch {
            when (ledgerRepository.deleteAttachment(attachmentId)) {
                is ApiResult.Success -> updateItem(expenseId, "expense") { row ->
                    row.copy(attachments = row.attachments?.filterNot { it.id == attachmentId })
                }
                else -> _actionError.update { "Could not delete the attachment. Try again." }
            }
        }
    }

    fun consumePendingOpen() {
        _pendingOpen.update { null }
    }

    private fun AttachmentDto.toRow(): AttachmentRow = AttachmentRow(
        id = id,
        filename = filename,
        sizeText = formatBytes(size_bytes),
        contentType = content_type,
        isMine = uploaded_by_user_id == myId,
    )

    private fun updateAttachment(
        expenseId: Long,
        attachmentId: Long,
        updater: (AttachmentRow) -> AttachmentRow,
    ) {
        updateItem(expenseId, "expense") { row ->
            row.copy(
                attachments = row.attachments?.map {
                    if (it.id == attachmentId) updater(it) else it
                },
            )
        }
    }

    private fun updateItem(id: Long, type: String, updater: (LedgerRow) -> LedgerRow) {
        val currentState = _state.value as? RelationshipDetailState.Loaded ?: return
        val newItems = currentState.data.ledgerItems.map {
            if (it.id == id && it.type == type) updater(it) else it
        }
        _state.update { currentState.copy(data = currentState.data.copy(ledgerItems = newItems)) }
    }

    private fun BalanceViewDto.toLine(
        myId: Long,
        currencyCode: String,
        counterpartyName: String,
    ): BalanceLine {
        if (netCents == 0L || fromUserId == null || toUserId == null) {
            return BalanceLine(
                settled = true,
                youOwe = false,
                amountText = formatCents(0, currencyCode),
                counterpartyName = counterpartyName,
            )
        }
        // Backend convention: `from_user_id` owes `to_user_id`.
        val youOwe = fromUserId == myId
        return BalanceLine(
            settled = false,
            youOwe = youOwe,
            amountText = formatCents(netCents, currencyCode),
            counterpartyName = counterpartyName,
        )
    }

    companion object {
        fun factory(
            locator: ServiceLocator,
            relationshipId: Long,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                RelationshipDetailViewModel(
                    relationshipId,
                    locator.relationshipRepository,
                    locator.ledgerRepository,
                    locator.meRepository,
                )
            }
        }

        @Suppress("unused")
        private fun unusedExtras(extras: CreationExtras) = extras
    }
}
