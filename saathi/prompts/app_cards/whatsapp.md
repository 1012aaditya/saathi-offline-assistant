WhatsApp (com.whatsapp) — hints from real screens:
- Home: chat rows show the contact or group name with its last message preview; the search bar is rid=search_bar_inner_layout (desc "Ask Meta AI or Search"). Don't use "Message your assistant" (Meta AI) or "New chat".
- To open a chat: tap the row whose text starts with the contact name. If the name isn't visible, tap search, type the name, then tap the matching result.
- Inside a chat: the contact name is rid=conversation_contact_name; messages are rid=message_text with the time in rid=date. Each message is tagged `from=them` (received from the contact) or `from=me` (sent by the user). The text box is rid=entry.
- "<Name> ka last message" means the last message the CONTACT sent: the lowest `from=them` message on screen (largest y). Ignore `from=me` messages — those are the user's own words. If no `from=them` message is visible, scroll up.
- Reading the last message: once found, use `done` and quote that message text exactly.
- Sending is protected: the app asks the user to confirm before any send. Never tap the payment (₹) icon or call buttons.
- A bottom sheet such as "Turn on notifications for WhatsApp" hides the screen behind it: close it with rid=cancel ("Close").
