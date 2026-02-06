# Android Localization Extension

[![Buy Me A Coffee](https://img.shields.io/badge/Buy%20Me%20A%20Coffee-Support-yellow?style=for-the-badge&logo=buy-me-a-coffee)](https://www.buymeacoffee.com/zigzagyc)

This IntelliJ/Android Studio plugin automatically adds localization to your Android project by translating `strings.xml` resources to a target language. It supports multiple translation providers including OpenAI (ChatGPT), Gemini, Grok, Google Translate, and Microsoft Translator.

## Features

-   **Multi-Service Support**: Choose from OpenAI, Gemini, Grok, Google Translate, or Microsoft Translator.
-   **Selective Translation**: View all strings in your project and select exactly which ones to translate.
-   **Context-Aware**: Provide optional context or glossary rules (e.g., "Maintain capitalization for app names") to guide LLM-based translations.
-   **Secure**: API keys are handled securely at runtime and not stored in code.

## Installation

### Building from Source

1.  Open the project in IntelliJ IDEA or Android Studio.
2.  Open the **Gradle** tool window.
3.  Run the task `Tasks > intellij > buildPlugin`.
4.  The generated plugin file (`android-localization-extension-1.0-SNAPSHOT.zip`) will be located in `build/distributions/`.

### Installing the Plugin

1.  Open Android Studio.
2.  Go to **Settings/Preferences** > **Plugins**.
3.  Click the **Gear icon** and select **Install Plugin from Disk...**.
4.  Select the `.zip` file generated in the previous step.
5.  Restart Android Studio.

## Usage

1.  Open your Android project.
2.  Navigate to **Tools** > **Translate Android Strings** in the main menu.
3.  In the dialog:
    -   **Translation Service**: Select your preferred provider (e.g., Gemini).
    -   **API Key**: Enter your valid API key for the selected service.
    -   **Target Language**: Enter the target language code (e.g., `es` for Spanish, `fr` for French).
    -   **Context / Glossary**: (Optional) Enter any specific instructions for the translator.
    -   **Select strings**: Check the boxes next to the strings you want to translate in the table.
4.  Click **OK**.
5.  The plugin will process the translations in the background and generate/update the corresponding `values-<lang>/strings.xml` file.

## Getting API Keys

### Google Translate
1.  Go to the [Google Cloud Console](https://console.cloud.google.com/).
2.  Create a new project or select an existing one.
3.  Navigate to **APIs & Services** > **Library**.
4.  Search for **"Cloud Translation API"** and enable it.
5.  Go to **APIs & Services** > **Credentials**.
6.  Click **Create Credentials** > **API Key**.
7.  Copy the generated key.
    *   *Note: You may need to set up a billing account, though there is a free tier.*

### OpenAI (ChatGPT)
1.  Go to [platform.openai.com](https://platform.openai.com/).
2.  Sign up or log in.
3.  Navigate to **API Keys** and create a new secret key.

### Gemini (Google AI)
1.  Go to [Google AI Studio](https://aistudio.google.com/).
2.  Click **Get API key**.
3.  Create a key in a new or existing Google Cloud project.

### Microsoft Translator
1.  Go to the [Azure Portal](https://portal.azure.com/).
2.  Create a resource for **"Translator"**.
3.  Go to the resource's **Keys and Endpoint** section to find your key.

### Grok (xAI)
1.  Go to the [xAI Console](https://console.x.ai/).
2.  Create an API key.

## Support

If you find this plugin helpful, consider supporting its development:

[![Buy Me A Coffee](https://img.shields.io/badge/Buy%20Me%20A%20Coffee-Support-yellow?style=for-the-badge&logo=buy-me-a-coffee)](https://www.buymeacoffee.com/zigzagyc)