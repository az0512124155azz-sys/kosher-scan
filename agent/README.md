# מרכז הבדיקות והסוכן

אין צורך לקנות או להחזיק שרת. השירות מיועד ל־Cloudflare Workers, עם דשבורד
ומאגר D1. האפליקציה ממשיכה לעבוד גם כשהשירות אינו מחובר.

השירות הפעיל: https://kosher-scan-agent.az0512124155azz.workers.dev
החיבור האוטומטי פורסם עבור גרסה 1.7.0. קוד הניהול נשמר בקובץ פרטי מקומי
`agent/local-settings.json`, שמוחרג מ־Git. הבוט הפעיל הוא
[@kosher_scan_review_2026_bot](https://t.me/kosher_scan_review_2026_bot), והוא
מחובר לשיחה הפרטית של הבעלים. מסירת הודעות ותמונת JPEG נבדקה בפועל.

## מה כבר עובד בקוד

- כשהשירות הופעל והאפליקציה מציגה ״לא ידוע״, היא שומרת משלוח ברקע עם הברקוד,
  פרטי המוצר ותמונתו אם נמצאו, וצילום חתוך של אזור הברקוד שנסרק.
  בהקלדה ידנית אין צילום ברקוד. רק תוצאות לא ידועות נשלחות.
- כל סריקה מקבלת רשומת משלוח, גם אם אותו מוצר כבר נשלח. ניסיונות חוזרים
  של אותו משלוח אינם יוצרים כפילות. המחקר על אותו ברקוד ומדינת רכישה משותף.
- Gemini מחפש מידע ומציג הצעה ומקורות בדשבורד. הצעת AI אינה אישור כשרות.
  תשובת כשר/לא כשר מתפרסמת אחרי אימות בדשבורד, עם מקור ותוקף, רק עבור
  הברקוד ומדינת הרכישה שנבדקו. סתירה למאגר אחר נשארת ״לא ידוע״.
- הבוט שולח את תמונת הברקוד, המספר ופרטי/תמונת המוצר לשיחה הפרטית שחוברה.
  יש לחבר בוט אמיתי כדי להפעיל את המסלול הזה.
- האפליקציה בודקת תשובה חדשה במשך כ־30 שניות אחרי התוצאה, ומאפשרת בדיקה
  של תשובה בסריקה עתידית. אין כפתור סוכן או הגדרות חיבור אצל משתמשים.

## מה דרוש להפעלה באינטרנט

1. ליצור חשבון ב־[Cloudflare](https://dash.cloudflare.com/sign-up).
2. ליצור בוט דרך [BotFather](https://t.me/BotFather), באמצעות `/newbot`.
   לשמור את מפתח הבוט בקובץ פרטי מקומי; לא לפרסם ב־GitHub או לשלב ב־APK.
3. להתחבר לחשבון, ליצור D1, להכניס את המזהה האמיתי ל־`wrangler.jsonc`,
   להעלות secrets ולהפעיל את השירות. הפקודות הבאות מיועדות למי שמבצע את ההתקנה.

```powershell
cd agent
npm ci
npx wrangler login
npx wrangler d1 create kosher-scan-agent
# Copy the returned database_id into wrangler.jsonc.
npx wrangler d1 migrations apply DB --remote
# Repeat for GEMINI_KEYS, ADMIN_TOKEN, APP_TOKEN, TELEGRAM_TOKEN,
# TELEGRAM_WEBHOOK_SECRET and TELEGRAM_PAIR_CODE. Enter each value privately.
npx wrangler secret put GEMINI_KEYS
npx wrangler secret put ADMIN_TOKEN
npx wrangler secret put APP_TOKEN
npx wrangler secret put TELEGRAM_TOKEN
npx wrangler secret put TELEGRAM_WEBHOOK_SECRET
npx wrangler secret put TELEGRAM_PAIR_CODE
npm run deploy
```

`GEMINI_KEYS` הוא מערך JSON של מפתחות. הקודים האחרים צריכים להיות ערכים
אקראיים נפרדים; סקריפט ההגדרה המקומי מייצר אותם. מפתח הבוט מגיע מ־BotFather.
המזהה בקונפיגורציה שבמאגר מצביע כעת על המאגר הפעיל בחשבון הבעלים. להתקנה
בחשבון אחר יש להחליף אותו במזהה חדש.

4. לפתוח את הכתובת ש־Cloudflare מחזיר ולהיכנס עם קוד הניהול.
5. במסך ״הגדרות השירות והבוט״ להפעיל את החיבור לטלגרם, ואז לשלוח לבוט
   את פקודת החיבור שמופיעה בדשבורד. רק השיחה המחוברת מקבלת מידע.
6. מנהל המערכת מריץ `node scripts/publish-connection.mjs https://your-service.workers.dev/`
   אחרי הפריסה. הסקריפט מאמת שהשירות פעיל ושהקוד המוגבל מתקבל, ואז מכין
   `agent/connection.json`. יש להעלות קובץ זה ל־main. כל אפליקציה חדשה קוראת
   אותו אוטומטית בפתיחה, ושומרת מטמון עד 24 שעות לתקלות רשת.
   אין כתובת, קוד חיבור, בחירת מדינה, כפתור סוכן או ״בדיקה מעמיקה״ באפליקציה.
   היקף השוק קבוע לישראל; אין להסיק התאמה לשוק בריטי מתוך נתוני KLBD.

קוד החיבור לאפליקציה הוא ציבורי במכוון ומוגבל להגשה וקריאת תשובות. הוא
אינו מפתח Gemini/Telegram/GitHub או קוד ניהול. מכסות השירות חלות גם עליו.
כיום `connection.json` מציין `enabled:true` עבור השירות שנפרס ונבדק בפועל.
להפסקת קליטת בדיקות יש לפרסם `enabled:false`; אפליקציות מעדכנות את התצורה
בפתיחה הבאה כאשר הרשת זמינה. עדכון זה אינו מבטל משלוח שכבר נשמר בתור.

האירוח עשוי להתאים למסלול החינמי בהתאם למכסות Cloudflare. שימוש ב־Gemini
וחיפוש Google כפופים למכסות ולתמחור של Google; אין הבטחה שכל המערכת בחינם.
ברירת המחדל מגבילה מחקר ל־100 קריאות ביום ומשלוחים חדשים ל־500 ביום.
השירות אינו מעקף למכסות: אין ניסיון עם מפתח אחר אחרי שגיאת מכסה.

## פיתוח ובדיקות

```powershell
npm ci
node scripts/configure.mjs "C:/path/to/private-gemini-key-file.txt"
npm run migrate:local
npm run dev -- --ip 127.0.0.1
npm test
npm run check
```

הייבוא קורא מפתחות AQ/AIza מקובץ מקומי ומייצר `.dev.vars` ו־`local-settings.json`
שמוחרגים מ־Git. אין להריץ שוב על התקנה קיימת בלי כוונה לשנות קודי חיבור.
כדי להוסיף טלגרם מקומי יש להגדיר `TELEGRAM_TOKEN` ב־`.dev.vars`; webhook דורש
כתובת HTTPS ציבורית, ולכן ההפעלה הרגילה מתבצעת אחרי פריסה.
אמולטור Android יכול להתחבר ל־`http://10.0.2.2:8787` רק בגרסת debug.
גרסת release מחייבת HTTPS.

תמונות חתוכות נמחקות מהשירות אחרי 30 יום; ניתן למחוק בדיקה בדשבורד קודם.
משלוחים מקומיים נמחקים אחרי הצלחה/כישלון סופי; קבצים ישנים מנוקים בהוספת
משלוח חדש אחרי 7 ימים. קודי Gemini/Telegram נשארים בצד השירות בלבד.
קוד החיבור לאפליקציה מאפשר הגשת בדיקות וקריאת תשובות, ולא ניהול הדשבורד.
Cron בכל דקה משחזר עבודה שנקטעה. ייתכנו הודעות Telegram חוזרות אם השירות
נקטע אחרי שטלגרם קיבל את ההודעה ולפני שנרשמה ההצלחה.

Telegram sends one compact notification per case: barcode and product photos form one album with a single caption. Repeated scans stay in observations and increment the dashboard count without sending another alert. Migration 0003 remembers previously delivered cases. A new case after expiry can receive a new alert.
