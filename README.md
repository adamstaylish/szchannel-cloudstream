# SZChannel CloudStream Provider

إضافة CloudStream لموقع SZChannel، مبنية بموافقة فريق الموقع.

## التشغيل المحلي

```bash
./gradlew :SZChannel:assembleRelease
```

سيظهر ملف APK للإضافة داخل:

```text
SZChannel/build/outputs/apk/release/
```

## ملاحظات

- يعتمد المزوّد على صفحات التصنيفات وملفات JavaScript التي ينشرها الموقع لقوائم الحلقات.
- تمرّر روابط الخوادم المعلنة في صفحة الحلقة إلى مستخرجات CloudStream.
- عند تغيير نطاق الموقع أو محددات HTML، حدّث `mainUrl` وCSS selectors في `SZChannel.kt`.
- للنشر كمستودع CloudStream، يجب رفع ملفات البناء إلى فرع `builds` ثم إنشاء `repo.json` يشير إلى `plugins.json`.
