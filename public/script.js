
const form = document.getElementById("transaction-form");
const list = document.getElementById("transaction-list");
const message = document.getElementById("form-message");
const clearButton = document.getElementById("clear-button");

const money = value =>
  new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR"
  }).format(value);

function escapeHTML(value) {
  return String(value).replace(/[&<>"']/g, char => ({
    "&": "&amp;",
    "<": "&lt;",
    ">": "&gt;",
    '"': "&quot;",
    "'": "&#39;"
  })[char]);
}

function localDate() {
  const now = new Date();
  now.setMinutes(now.getMinutes() - now.getTimezoneOffset());
  return now.toISOString().slice(0, 10);
}

document.getElementById("date").value = localDate();

async function request(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      ...(options.headers || {})
    }
  });

  if (!response.ok) {
    const result = await response.json().catch(() => ({}));
    throw new Error(result.error || "Request failed. Please try again.");
  }

  return response.json();
}

async function loadTransactions() {
  list.innerHTML = '<p class="empty-state">Loading transactions...</p>';

  try {
    const transactions = await request("/api/transactions");
    renderTransactions(transactions);
  } catch (error) {
    list.innerHTML =
      '<p class="empty-state">Could not load transactions. Check that the server is running.</p>';
  }
}

function renderTransactions(transactions) {
  const income = transactions
    .filter(t => t.type === "income")
    .reduce((sum, t) => sum + Number(t.amount), 0);

  const expenses = transactions
    .filter(t => t.type === "expense")
    .reduce((sum, t) => sum + Number(t.amount), 0);

  document.getElementById("balance").textContent = money(income - expenses);
  document.getElementById("income").textContent = money(income);
  document.getElementById("expenses").textContent = money(expenses);

  if (transactions.length === 0) {
    list.innerHTML =
      '<p class="empty-state">No transactions yet. Add your first one!</p>';
    return;
  }

  const sorted = [...transactions].sort((a, b) =>
    b.date.localeCompare(a.date)
  );

  list.innerHTML = sorted.map(t => {
    const isIncome = t.type === "income";
    const symbol = isIncome ? "+" : "−";
    const icon = isIncome ? "↙" : "↗";
    const note = t.note
      ? `<small>${escapeHTML(t.note)}</small>`
      : "";

    return `
      <div class="transaction">
        <div class="transaction-info">
          <span class="transaction-icon">${icon}</span>
          <div>
            <strong>${escapeHTML(t.category)}</strong>
            <small>${escapeHTML(t.date)}</small>
            ${note}
          </div>
        </div>
        <div class="transaction-right">
          <div class="transaction-amount ${isIncome ? "income" : "expense"}">
            ${symbol}${money(Number(t.amount))}
          </div>
          <button class="delete-button"
                  type="button"
                  data-id="${escapeHTML(t.id)}">
            Delete
          </button>
        </div>
      </div>
    `;
  }).join("");
}

form.addEventListener("submit", async event => {
  event.preventDefault();
  message.textContent = "Saving...";
  
  const transaction = {
    type: document.getElementById("type").value,
    amount: Number(document.getElementById("amount").value),
    category: document.getElementById("category").value,
    date: document.getElementById("date").value,
    note: document.getElementById("note").value.trim()
  };

  if (!Number.isFinite(transaction.amount) || transaction.amount <= 0) {
    message.textContent = "Enter an amount greater than zero.";
    return;
  }

  try {
    await request("/api/transactions", {
      method: "POST",
      body: JSON.stringify(transaction)
    });

    form.reset();
    document.getElementById("date").value = localDate();
    message.textContent = "Transaction added successfully!";
    await loadTransactions();
  } catch (error) {
    message.textContent = error.message;
  }
});

list.addEventListener("click", async event => {
  const button = event.target.closest(".delete-button");
  if (!button) return;

  if (!confirm("Delete this transaction?")) return;

  try {
    await request(
      "/api/transactions/" + encodeURIComponent(button.dataset.id),
      { method: "DELETE" }
    );
    await loadTransactions();
  } catch (error) {
    alert(error.message);
  }
});

clearButton.addEventListener("click", async () => {
  if (!confirm("Delete ALL transactions? This cannot be undone.")) return;

  try {
    await request("/api/transactions", { method: "DELETE" });
    message.textContent = "";
    await loadTransactions();
  } catch (error) {
    alert(error.message);
  }
});

loadTransactions();
