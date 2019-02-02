# eclipse-style-i18n-plugin

I18nize string literals in Eclipse style.

This plugin is applicable for Java source code.

**Before**
```java
public class Computer {
    public static void main(String[] args) {
        System.out.println("Hello, world!");
    }
}
```

**After**
```java
public class Computer {
    public static void main(String[] args) {
        System.out.println(Messages.getString("Computer.hello.world")); //$NON-NLS-1$
    }
}
```

## Install

1. Download the latest version from [releases](https://github.com/old-horizon/eclipse-style-i18n-plugin/releases).
1. [Open plugin settings](https://www.jetbrains.com/help/idea/managing-plugins.html) and choose "Install plugin from Disk".
1. Specify the file you downloaded.
1. Restart IntelliJ IDEA.

## Usage

1. Move cursor to the target string literal.
1. Press `Alt + Enter` to open the list of intention actions.
1. Select "Eclipse style i18n".
1. Choose the action: externalize or ignore.

## License

MIT
