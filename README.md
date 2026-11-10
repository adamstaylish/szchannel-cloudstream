# SZChannel CloudStream Providers

مستودع إضافة CloudStream الذي يسجّل أربعة مزوّدات مستقلة: SZChannel وStardima وAnimeZid وEgyDead.

## البناء محليًا

```bash
./gradlew :SZChannel:make
```

ينشئ Gradle ملف إضافة CloudStream بصيغة `.cs3` وملفات البيانات الوصفية داخل `SZChannel/build/`.

## ملاحظات

- يعتمد SZChannel على صفحات التصنيفات وملفات JavaScript التي ينشرها الموقع لقوائم الحلقات.
- تمرّر المزوّدات روابط المشغّلات المعلنة في صفحات المواقع إلى مستخرجات CloudStream.
- AnimeZid يعرض صفحات الأفلام والمسلسلات والحلقات، لكن صفحة المشاهدة التي فُحصت لم تعلن حاليًا عن iframe أو مصدر فيديو؛ لذلك لا يخترع المزوّد رابط تشغيل.
- صفحة الفيلم التي فُحصت في Stardima لم تعلن عن رابط تشغيل؛ تظهر بياناتها، ويستخرج المزوّد التشغيل فقط عند وجود رابط يعلنه الموقع.
- عند تغيير نطاق أحد المواقع أو محددات HTML، حدّث `mainUrl` ومحددات CSS في ملف المزوّد المعني.
- تُنشر الإضافة تلقائيًا إلى فرع `builds` عند تحديث الفرع `main`.
- أضف رابط المستودع `https://raw.githubusercontent.com/adamstaylish/szchannel-cloudstream/builds/repo.json` إلى CloudStream.
