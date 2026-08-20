> This repository is a maintained fork of [PortSwigger/customizer](https://github.com/PortSwigger/customizer).
>
> It adds bundled Catppuccin themes, custom `.theme.json` loading, and compatibility fixes for current Burp Suite versions.
>
> Releases in this repository are maintained independently and are not official PortSwigger releases.

[Theme](/images/cat-burp-3.png)


<p align="center">
  <h1 align="center">Burp Customizer</h1>
  <h5 align="center">Because just a dark theme wasn't enough!</h5>
</p>

<hr>

<p align="center">
  <img src="https://img.shields.io/github/actions/workflow/status/falasi/customizer/gradle.yml?style=for-the-badge" alt="GitHub Workflow Status">
  <img src="https://img.shields.io/github/watchers/falasi/customizer?label=Watchers&style=for-the-badge" alt="GitHub Watchers">
  <img src="https://img.shields.io/github/stars/falasi/customizer?style=for-the-badge" alt="GitHub Stars">
  <img src="https://img.shields.io/github/downloads/falasi/customizer/total?style=for-the-badge" alt="GitHub Downloads">
  <img src="https://img.shields.io/github/license/falasi/customizer?style=for-the-badge" alt="GitHub License">
</p>

<hr>

**Originally created by [CoreyD97](https://github.com/CoreyD97/BurpCustomizer).**  
This fork is based on the PortSwigger-maintained version of Burp Customizer and includes additional theme and compatibility work.

_Everybody knows hackers only work at night, so for years people asked PortSwigger to implement a dark theme.  
When they did, hackers rejoiced everywhere! But some still wanted more... Until... Burp Customizer!_

Burp Suite 2020.12 replaced the old Look and Feel classes with FlatLaf, an open-source Look and Feel library that also supports third-party [themes developed for the IntelliJ Platform][1].

Burp Customizer allows these themes to be used in Burp Suite while also adapting Burp-specific UI properties so custom themes integrate more consistently with Burp's interface.

[1]: https://github.com/JFormDesigner/FlatLaf/tree/main/flatlaf-intellij-themes

## Themes

This fork supports:

- All bundled [FlatLaf IntelliJ themes][1].
- All four [Catppuccin][2] flavors:
  - Latte
  - Frappé
  - Macchiato
  - Mocha
- Custom IntelliJ / FlatLaf `.theme.json` or `.json` files loaded from disk using **Load Theme File...** in the Customizer tab.
- Theme persistence across Burp restarts.

[2]: https://catppuccin.com/palette

Every theme, whether bundled or loaded from disk, goes through the same theme pipeline:

```text
theme JSON
    ↓
FlatLaf IntelliJTheme
    ↓
Burp-specific UI property mapping
    ↓
Burp UI refresh
```

This allows Burp-specific UI elements to inherit colors from the active theme instead of falling back to Burp's default palette where possible.

## Fork changes

Changes maintained in this fork include:

- Bundled Catppuccin Latte, Frappé, Macchiato, and Mocha themes.
- Custom `.theme.json` / `.json` theme loading.
- Compatibility updates for modern Burp Suite Look and Feel classes.
- Runtime discovery of Burp's current theme classes.
- Improved handling of Burp-specific UI defaults.
- Semantic mapping of Burp-specific background, selection, border, accent, and editor colors.
- Compatibility fixes for current Burp Suite versions while preserving Proxy, Repeater, Logger, and other request/response viewers.

## Images

<table>
<tr>
<td>Atom One Dark</td>
<td>

![Atom One Dark Customizer](images/AtomOneDarkCustomizer.png)

</td>
<td>

![Atom One Dark Repeater](images/AtomOneDarkRepeater.png)

</td>
<td>

![Atom One Dark Logger](images/AtomOneDarkLogger.png)

</td>
</tr>

<tr>
<td>Dark Purple</td>
<td>

![Dark Purple Customizer](images/DarkPurpleCustomizer.png)

</td>
<td>

![Dark Purple Repeater](images/DarkPurpleRepeater.png)

</td>
<td>

![Dark Purple Logger](images/DarkPurpleLogger.png)

</td>
</tr>

<tr>
<td colspan="4" align="right">And many more themes!</td>
</tr>
</table>

## Limitations

Burp uses a number of custom GUI components and Look and Feel properties that do not have direct equivalents in standard FlatLaf themes.

Burp Customizer maps these Burp-specific properties to semantically similar theme properties where possible. Some themes may still contain elements that do not perfectly match the rest of the interface.

The correct Burp base theme should also be selected first so Burp uses the appropriate icon set:

```text
Settings → User interface → Display
```

Use Burp's Dark base theme with dark custom themes and Burp's Light base theme with light custom themes.

If you find an element that does not fit the active theme, please open an issue with:

- the theme name
- your Burp Suite version
- a screenshot
- the affected Burp component

## Installing

Download the latest release from this fork:

https://github.com/falasi/customizer/releases

Then:

1. Open Burp Suite.
2. Go to **Extensions**.
3. Add `BurpCustomizer.jar` as a Java extension.
4. Open the **Customizer** tab.
5. Select a bundled theme or load your own `.theme.json` file.

Latest release:

https://github.com/falasi/customizer/releases/tag/v1.3.1

## Usage

1. Select the appropriate Burp base theme under:

   ```text
   Settings → User interface → Display
   ```

2. Open the **Customizer** tab.

3. Select a bundled FlatLaf or Catppuccin theme.

4. To use your own IntelliJ / FlatLaf theme, choose:

   ```text
   Load Theme File...
   ```

5. Select a `.theme.json` or `.json` file.

6. The selected theme is remembered across Burp restarts.

## Building

Clone your fork:

```bash
git clone https://github.com/falasi/customizer.git
cd customizer
```

Build:

```bash
./gradlew clean jar
```

The release JAR is generated at:

```text
./releases/BurpCustomizer.jar
```

For local development against Burp, place your Burp Professional JAR in the project directory as:

```text
burpsuite_pro.jar
```

The Burp JAR is used only as a compile-time dependency and should not be committed to the repository.

## Credits

- Original Burp Customizer: [CoreyD97/BurpCustomizer](https://github.com/CoreyD97/BurpCustomizer)
- PortSwigger-maintained fork: [PortSwigger/customizer](https://github.com/PortSwigger/customizer)
- FlatLaf: https://www.formdev.com/flatlaf/
- Catppuccin: https://catppuccin.com/
- All bundled theme credits belong to their respective original authors.

## License

This project retains the original project's license. See [LICENCE](LICENCE) for details.

