# SZChannel CloudStream Provider

مستودع إضافة CloudStream لموقع SZChannel.

## البناء محليًا

```bash
./gradlew :SZChannel:make
```

ينشئ Gradle ملف إضافة CloudStream بصيغة `.cs3` وملفات البيانات الوصفية داخل `SZChannel/build/`.

## ملاحظات

- يعتمد المزوّد على صفحات التصنيفات وملفات JavaScript التي ينشرها الموقع لقوائم الحلقات.
- تمرّر روابط الخوادم المعلنة في صفحة الحلقة إلى مستخرجات CloudStream.
- عند تغيير نطاق الموقع أو محددات HTML، حدّث `mainUrl` وCSS selectors في `SZChannel.kt`.
- تُنشر الإضافة تلقائيًا إلى فرع `builds` عند تحديث الفرع `main`.
- أضف رابط المستودع `https://raw.githubusercontent.com/adamstaylish/szchannel-cloudstream/builds/repo.json` إلى CloudStream.
