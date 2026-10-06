UPDATE cases SET telegram_sent=0, telegram_delivery_at=0
WHERE phase='review' AND ai_error='' AND ai_json NOT IN ('','{}');
