## [1.0.7]
- Added Anthropic Claude translation service integration (`claude-5-sonnet`, `claude-3-7-sonnet`, etc.).
- Overhauled layout preview screenshot engine to resolve all project string resources and render accurate horizontal/vertical UI layouts with multi-line text wrapping.
- Implemented universal 404 model fallback loops and 3-layer Quota Exceeded partial result preservation.

## [1.0.6]
- Added dynamic AI model fetching per vendor (OpenAI, Gemini, Grok) with live API key queries and automatic 404 fallback handling.

## [1.0.5]
- Added existing translation verification, interactive diff selection, static layout adaptability suggestions, and static layout XML screenshot capture.

## [1.0.4]
- Fixed module compatibility for JetBrains Gateway and non-Java IDE environments by removing unnecessary mandatory Java module dependency.
- Added existing target language translation verification and interactive diff selection (Keep Existing vs Use New).
- Added static layout XML container size analysis with dual text shortening & layout adaptability suggestions.
- Added static layout XML screenshot capture feature (`screenshots/<targetLang>/<layout_name>.png`).

## [1.0.3]
- Enhanced platform compatibility across all current and future versions of Android Studio and IntelliJ IDEA.
- Added context-aware length checks and abbreviation suggestions.

## [1.0.2]
- Feature updates and stability fixes.

## [1.0.1]
- Fixed deprecated API usage (`Project.getBaseDir()`) to ensure compatibility with future IDE versions.

## [1.0.0]
- Initial release of Android Localization Assistant.
