# Android F-Droid Packaging

## Metadata Draft

`metadata/dev.hyphen.android.yml` is a disabled F-Droid metadata draft for HYP-M5-007. It is intentionally not submission-ready until the public repository URL, issue tracker, release tag, final app name, formal root license files, dependency audit, and signing/update strategy are complete.

Local checks available in this environment:

```bash
ruby -e 'require "yaml"; YAML.load_file("packaging/android-fdroid/metadata/dev.hyphen.android.yml")'
./scripts/check.sh
```

When `fdroidserver` is available, run the F-Droid-native checks from a proper fdroiddata checkout:

```bash
fdroid rewritemeta dev.hyphen.android
fdroid lint dev.hyphen.android
```

Do not submit the disabled draft as-is. Replace placeholder public URLs and the draft commit with the release tag or final full commit hash first.
