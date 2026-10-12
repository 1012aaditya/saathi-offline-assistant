/* One switch for the whole site.
   Set paymentsLive to true once the UPI payment flow works end to end on the phone
   (reaches the PIN handoff with a correct read-back). Every page reads this. */
window.SAATHI_CONFIG = { paymentsLive: false };
document.documentElement.setAttribute('data-payments', window.SAATHI_CONFIG.paymentsLive ? 'live' : 'soon');
