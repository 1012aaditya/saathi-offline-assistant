Blinkit (com.grofers.customerapp) — hints from real screens:
- Search: tap the search bar (rid=qd_search_bar, desc "Search for atta, dal, coke and more"), then `type` the product in simple English (e.g. "amul milk 1 litre"), even if the user spoke Hindi. Suggestions appear as rows (rid=tv_text); tapping one shows product results.
- Product cards show name, size and price (₹); the add button is labelled "ADD". Product cards can be unlabeled: use `look` if the element list is not enough to tell products apart.
- If several products match equally well (e.g. Amul Taaza vs Amul Gold, 500 ml vs 1 l) and the user didn't say which, use `ask_user` with the 2–3 options and prices, quoting names exactly.
- Add exactly the quantity asked (default 1). After "ADD", the button turns into a − / + counter. Do not tap − (it removes the item).
- Then open the cart ("View cart" / cart bar at the bottom) and tap the checkout button ("Proceed", "Proceed to pay", "Place order"). Saathi's safety layer stops there, reads the total back and asks the user — never try to get around it.
- Dismiss offers / pop-ups / location prompts with "Not now", "Skip", "Close" or `back`. Never change the delivery address or pick a payment method.
