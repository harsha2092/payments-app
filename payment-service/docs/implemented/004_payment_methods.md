# Payment Methods & Hybrid Checkout Strategy

## 1. Overview
This document outlines the strategy for processing payments, handling hybrid (split) payment attempts, and defining the user experience for the checkout flow. It adheres to industry standard practices for e-commerce and financial systems, aligning with the "Modular Monolith" architecture defined in `hybrid_orders.md`.

## 2. Order Creation Timing
**Decision:** The Order ID **must** be created *before* exposing the payment methods to the user.

**Industry Standard Justification:**
1. **Idempotency & Retryability:** External payment gateways (e.g., Stripe, Razorpay) require a stable identifier. If a user encounters a network error and retries, having a pre-existing Order ID ensures we do not double-bill them or create duplicate orders on the payment provider side.
2. **State Management:** Tracking multiple payment attempts requires a parent entity. Generating the `Order` explicitly anchors all `PaymentAttempt` records, enabling accurate tracking of split or failed payments.
3. **Price & Inventory Locking:** Creating the order acts as a lock for the `totalAmount`, ensuring it cannot be tampered with or affected by simultaneous cart updates in other tabs.
4. **Analytics:** It allows tracing of "Abandoned Checkouts" by identifying `Order`s that remain in the `CREATED` state without any successful `PaymentAttempt`s.

## 3. Available Payment Methods
The system supports the following methods:
- **INTERNAL_WALLET**: Deducted instantly. Entirely systemic and requires no human intervention or external gateway redirects.
- **UPI**: External gateway (Synchronous/Asynchronous webhook-based).
- **NETBANKING**: External gateway (Asynchronous redirect-based).
- **CARD**: External gateway (Synchronous/Asynchronous).

## 4. Hybrid Payment Rules
**Rule:** A single checkout transaction can utilize a maximum of **two** payment methods, subject to strict combination rules:
- **Valid Combinations**: 
  - `WALLET` + `UPI`
  - `WALLET` + `NETBANKING`
  - `WALLET` + `CARD`
  - `WALLET` (Only)
  - `UPI`, `NETBANKING`, or `CARD` (Only)
- **Invalid Combinations**: `UPI` + `CARD`, `NETBANKING` + `CARD`, `UPI` + `NETBANKING`, etc.

*Reasoning: Combinations of multiple external gateways introduce extreme complexity regarding refunds, race conditions (multiple webhooks firing async), and user experience. The wallet serves as a fast, internal buffer that can cleanly be split with exactly one external provider.*

## 5. User Interface (UI) Logic & Presentation
To enforce these rules robustly, the frontend should present the checkout options based on the user's available balances:

1. **Calculate State:** Fetch the user's current `walletBalance` and the Order's `remainingAmount` (`totalAmount` - `paidAmount`).
2. **Wallet Presentation:** 
   - Display a prominent checkbox or toggle: `[ ] Apply Wallet Balance (Available: $X)`.
3. **Dynamic Amount Evaluation:**
   - If the Wallet is selected, systemic math executes: `newRemaining = remainingAmount - min(remainingAmount, walletBalance)`.
   - If the Wallet is *not* selected, `newRemaining = remainingAmount`.
4. **External Methods Presentation:**
   - If `newRemaining == 0` (Wallet covers entire order), **hide** or disable all other payment methods (UPI, Card, Netbanking). Display a singular "Pay with Wallet" button.
   - If `newRemaining > 0`, present the layout of external methods as **Radio Buttons** (mutually exclusive selection). The user MUST pick exactly one of [UPI, Netbanking, Card] to cover the `newRemaining` balance.

## 6. Execution Flow (Backend Mapping)
When the user submits a hybrid payment (`WALLET` + `EXTERNAL`), the client orchestration executes two API calls:

1. **Attempt 1 (Wallet):**
   - Client sends `POST /orders/{id}/payments { amount: walletDeduction, paymentMethod: "WALLET", vendor: "INTERNAL" }`.
   - Processed immediately by the backend without user intervention.
   - Database side-effect: `PaymentAttempt` created and marked `SUCCESS`. Order `paidAmount` increases. Order status transitions from `CREATED` to `PARTIALLY_PAID`.

2. **Attempt 2 (External Gateway):**
   - Client sends `POST /orders/{id}/payments { amount: newRemaining, paymentMethod: "SELECTED_EXTERNAL_METHOD", vendor: "STRIPE/RAZORPAY" }`.
   - Backend creates a `PENDING` `PaymentAttempt`.
   - Returns a gateway intent/token to the frontend, which handles the redirection or component rendering required for Card/UPI/Netbanking.

## 7. Retry & Failure Handling
Because the Wallet is systemic, it succeeds immediately. If external Attempt 2 fails (e.g., Card declined), the flow naturally protects the user and prevents payment conflicts:
1. The `Order` is safely stored as `PARTIALLY_PAID` (due to the successful Wallet attempt).
2. The user is kept on/returned to the checkout page. The frontend re-fetches the `Order`.
3. The UI sees `remainingAmount > 0`. The Wallet balance has already been allocated to this order and is no longer presented as an option.
4. The user is simply presented with the mutually exclusive `[UPI, Netbanking, Card]` radio buttons to retry paying the remaining balance.
5. Once the final retry succeeds via webhook or redirect success, the Order `paidAmount` hits `totalAmount`, and the status shifts to `PAID`.

## 8. Implementation Details
The backend enforces these constraints dynamically upon creation of a `PaymentAttempt` (`POST /orders/{id}/payments`):

1. **Amount Limit Validation**: The requested `amount` cannot exceed the `remainingAmount` (`totalAmount - paidAmount`) of the target order. This prevents overcharging.
2. **Hybrid Logic Validation (Method Exclusivity)**: The repository checks all existing `active` (non-`FAILED`) payment attempts. A strict constraint prevents the initialization of more than 1 active external method per session. Attempting to spawn a `UPI` attempt while a `CARD` attempt is pending fails with a sequence error.
3. **Wallet Auto-Execution (Internal systemic)**: Because `WALLET` represents an internal balance transaction, it bypasses the standard `PENDING` intermediate state. When requested, it executes instantly—defaulting its status to `SUCCESS` upon row creation, recording a `PaymentEvent`, and synchronously cascading to update the Order's `paidAmount` in `OrderService`.

## 9. Frontend API & Presentation Contract

### 9.1 Fetching Available Methods
To render the UI accurately, the client must pull the eligible payment methods and the user's financial context.
**Proposed API:** `GET /orders/{orderId}/eligible-methods` *(The backend seamlessly derives the `userId` context and applicable routing rules directly from the Order entity).*
**Payload Yields:**
- `supportedMethods`: An array of JSON objects defining active methods and their structural constraints:
  - `method`: String identifier (e.g., `"WALLET"`, `"UPI"`).
  - `balance`: Optional numerical value showcasing available funds specifically for ledger abstractions (`5000` for `WALLET`, `null` for external gateways).
  - `isSync`: Boolean denoting if execution is synchronous/instant (`true` for `WALLET`) or requires async webhook polling (`false` for `UPI`, `CARD`).
  - `executionPriority`: Number determining the frontend API dispatch order (`WALLET` is `1` and must be executed first, `CARD` is `2`).
  - `combinableWith`: Array of strings dictating which other methods can be seamlessly combined with it in a hybrid payment (e.g., `WALLET` can combine with `["UPI", "CARD", "NETBANKING"]`, but `UPI` can only combine with `["WALLET"]`).

### 9.2 Presentation & Execution Sequence
The sequence in which payments are presented and executed is designed to maximize checkout conversion and guarantee atomic safety:
- **Presentation Ordering (UI)**:
  1. **Primary**: `WALLET`. Presented as a prominent checkbox/toggle decoupled from the typical gateway selection. It is instantaneous and cost-free for the platform.
  2. **Secondary**: `UPI` (or `CARD`). Displayed as the default opened options in a radio group below the wallet.
  3. **Tertiary**: `NETBANKING`. Typically placed last or behind an accordion due to multi-step redirects.
- **Execution Strategy (Client Orchestration)**:
  - If a hybrid payment is selected (e.g. `WALLET` + `CARD`), the frontend **MUST** dispatch the `WALLET` API creation call first. 
  - **Why?**: The `WALLET` executes instantly on our internal servers. If it succeeds, the frontend calculates the true `remainingAmount` and safely fires the `CARD` API call. If the `WALLET` deduction fails (e.g., race condition in balance), the frontend halts the sequence entirely, preventing the user's `CARD` from being charged for a broken cart.

### 9.3 Client Error Handling & Invalid Scenarios
The backend strictly protects the ledger. If the UI orchestration glitches or is bypassed, the system rejects bad states:
- **Enforced Rule**: Maximum of one active external gateway method per order.
- **Throws `400 Bad Request` if**:
  - `UPI` + `CARD` is attempted simultaneously or sequentially (before one fails).
  - `CARD` + `NETBANKING` is attempted.
  - A client tries to charge an external method for an amount that exceeds the `remainingAmount` (e.g., charging the full cart value when the `WALLET` has already partially covered it).
