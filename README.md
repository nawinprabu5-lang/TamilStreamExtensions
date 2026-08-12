# TamilStreamExtensions

CloudStream extensions for Tamil movie sites — **Isaimini** and **IsaiDub**.

## 📁 Project Structure

```
TamilStreamExtensions/
├── build.gradle.kts              # Root build config (shared)
├── settings.gradle.kts           # Auto-discovers provider modules
├── gradle.properties             # Build settings
├── Isaimini/
│   ├── build.gradle.kts          # Module metadata
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── kotlin/com/tamilstream/
│           ├── IsaiminiPlugin.kt     # Entry point
│           └── IsaiminiProvider.kt   # Scraper logic
└── IsaiDub/
    ├── build.gradle.kts          # Module metadata
    └── src/main/
        ├── AndroidManifest.xml
        └── kotlin/com/tamilstream/
            ├── IsaiDubPlugin.kt      # Entry point
            └── IsaiDubProvider.kt    # Scraper logic
```

## 🔧 Building

### Prerequisites
- JDK 17+
- Android SDK (API 35)
- Gradle 8.x

### Build Commands
```bash
# Build all extensions
./gradlew assembleDebug

# Build specific extension
./gradlew :Isaimini:assembleDebug
./gradlew :IsaiDub:assembleDebug

# Build release .cs3 files
./gradlew make
```

The compiled `.cs3` files will be in each module's `build/` directory.

### Install in CloudStream
1. Build the `.cs3` file
2. Copy to your phone
3. Open CloudStream → Settings → Extensions → Install from file
4. Select the `.cs3` file

## 🌐 Supported Sites

### Isaimini (`isaimini.cfd`)
- **CMS**: DataLife Engine
- **Search**: POST-based
- **Content**: Bollywood, Hollywood, South Indian, Web Series
- **Stream**: Embedded player via IMDB ID (allmovieland.link)
- **Categories**: 14 categories including genres

### IsaiDub (`isaidub.movie`)
- **Domain check**: `isaidub.me` / `isaidub.co` redirect to current domain
- **Content**: Tamil Dubbed Movies, Hollywood, Web Series
- **Navigation**: Folder-based (Movie → Quality → File → Download)
- **Downloads**: Via `dubpage.xyz`
- **Categories**: Year-based, A-Z, genres, web series

## ⚙️ Extending

To add a new provider (e.g., Moviesda):
1. Create `Moviesda/` directory
2. Add `build.gradle.kts` with cloudstream metadata
3. Create `Plugin.kt` and `Provider.kt` in `src/main/kotlin/com/tamilstream/`
4. Add `AndroidManifest.xml`
5. Gradle auto-discovers the new module

## 📝 License
MIT
