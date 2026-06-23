# Getting Started

## Backend Setup

1. **Install dependencies:**
   Navigate to the `backend/` directory and run:
   ```bash
   pip install -e ".[dev]"
   ```
2. **Database Initialization:**
   Run migrations to setup the SQLite database:
   ```bash
   flask db upgrade
   ```
3. **Run the Development Server:**
   ```bash
   flask run --debug
   ```
   The API will be available at `http://localhost:5000`.
4. **Testing and Linting:**
   ```bash
   pytest
   ruff check .
   ```

## Android Client Setup

1. **Configuration:**
   Open the `android/` directory in Android Studio. Ensure your SDK is updated to at least API 35 (or 36 as recommended).
2. **Build and Run:**
   Use the Gradle wrapper to build:
   ```bash
   ./gradlew assembleDebug
   ```
   Install on an emulator or device:
   ```bash
   ./gradlew installDebug
   ```
3. **Testing and Linting:**
   ```bash
   ./gradlew test lint
   ```
