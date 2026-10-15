package name.abuchen.portfolio.snapshot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.AccountTransaction;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.PortfolioTransaction;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.SecurityPrice;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.CurrencyConverter;
import name.abuchen.portfolio.money.Money;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.util.Dates;

/**
 * Calculates the valuation of a client for a given date.
 * <p/>
 * This class incrementally applies the transactions to the positions to
 * determine the current funds (for accounts) the the current shares (for
 * securities). Therefore, the method nextValuation must always be called with a
 * date later the date of the previous call.
 */
/* package */ final class ClientSnapshotIterator
{
    private static class AccountPosition
    {
        private Account account;

        /** all account transactions */
        private List<AccountTransaction> transactions;

        /** the current index into the transaction list */
        private int index;

        /** the current funds after applying the transactions */
        private long funds;

        public AccountPosition(Account account)
        {
            this.account = account;
            this.transactions = new ArrayList<>(account.getTransactions());
            this.transactions.sort(Transaction.BY_DATE);
            this.index = 0;
            this.funds = 0;
        }
    }

    private static class SecurityPosition
    {
        private Security security;

        /** the current number of shares after applying the transactions */
        private long shares;

        /** the transactions that resulted in the given shares */
        private List<PortfolioTransaction> transactions;

        /** the option contract, if it is an option */
        private Optional<OptionChain.Contract> contract;

        public SecurityPosition(Security security)
        {
            this.security = security;
            this.transactions = new ArrayList<>();
            this.shares = 0;
        }

        private Optional<OptionChain.Contract> contract()
        {
            if (contract == null)
                contract = OptionChain.contractOf(security);
            return contract;
        }
    }

    /** the legs of one option chain held on a day */
    private static class ChainValuation
    {
        private int legs;
        private long value;
        private boolean stale;
        private boolean traded;
        /** the cash paid for the trades of the day, negative if received */
        private long cash;
        private final Map<OptionChain.Contract, Long> positions = new HashMap<>();
        private String currencyCode;
    }

    /**
     * A box spread valued as the loan (or deposit) it is: from its value after
     * the last trade to the value due at expiry, at a constant rate.
     */
    private record BoxValuation(LocalDate from, long value, LocalDate expiry, long expiryValue)
    {
        private long valueAt(LocalDate date)
        {
            long days = Dates.daysBetween(from, expiry);
            if (!date.isBefore(expiry) || days <= 0)
                return expiryValue;
            double share = (double) Dates.daysBetween(from, date) / days;
            if (value != 0 && Long.signum(value) == Long.signum(expiryValue))
                return Math.round(value * Math.pow((double) expiryValue / value, share));
            return Math.round(value + (expiryValue - value) * share);
        }
    }

    private final CurrencyConverter converter;

    private final List<AccountPosition> accounts;

    private final Map<Security, SecurityPosition> securities = new HashMap<>();
    private final List<PortfolioTransaction> portfolioTransactions;
    private int portfolioTransactionIndex = 0;

    /** the value of each option chain on the previous valuation date */
    private Map<String, Long> chainValues = new HashMap<>();

    /** the option chains that are box spreads, valued as loans */
    private final Map<String, BoxValuation> boxes = new HashMap<>();

    /** false until the first valuation date */
    private boolean valuedBefore;

    /* package */ ClientSnapshotIterator(Client client, CurrencyConverter converter)
    {
        this.converter = converter;

        this.portfolioTransactions = client.getPortfolios().stream().flatMap(p -> p.getTransactions().stream())
                        .sorted(Transaction.BY_DATE).toList();

        this.accounts = client.getAccounts().stream().map(AccountPosition::new).toList();
    }

    /* package */ long nextValuation(LocalDate date)
    {
        applyAccountTransactions(date);
        applyPortfolioTransactions(date);

        long valuation = 0;

        for (AccountPosition position : accounts)
        {
            if (position.funds == 0)
                continue;

            if (converter.getTermCurrency().equals(position.account.getCurrencyCode()))
            {
                valuation += position.funds;
            }
            else
            {
                valuation += converter.convert(date, Money.of(position.account.getCurrencyCode(), position.funds))
                                .getAmount();
            }
        }

        Map<String, ChainValuation> chains = new HashMap<>();

        for (SecurityPosition position : securities.values())
        {
            var contract = position.contract();

            // the cash of the day's trades, also of legs closed today
            if (contract.isPresent())
            {
                var c = chains.computeIfAbsent(contract.get().chainKey(), k -> new ChainValuation());
                for (int ii = position.transactions.size() - 1; ii >= 0; ii--)
                {
                    var tx = position.transactions.get(ii);
                    if (!tx.getDateTime().toLocalDate().equals(date))
                        break;
                    long amount = converter.convert(date, tx.getMonetaryAmount()).getAmount();
                    c.cash += tx.getType().isPurchase() ? amount : -amount;
                    c.traded = true;
                }
            }

            if (position.shares == 0)
                continue;

            SecurityPrice price = position.security.getSecurityPrice(date);
            boolean priced = price.getValue() != 0L && price.getDate().equals(date);

            if (price.getValue() == 0L)
            {
                // try to fallback to the price of the last // transaction
                PortfolioTransaction last = position.transactions.get(position.transactions.size() - 1);
                price = new SecurityPrice(last.getDateTime().toLocalDate(),
                                last.getGrossPricePerShare(converter.with(position.security.getCurrencyCode()))
                                                .getAmount());

            }

            long marketValue = BigDecimal.valueOf(position.shares) //
                            .movePointLeft(Values.Share.precision())
                            .multiply(BigDecimal.valueOf(price.getValue()), Values.MC)
                            .movePointLeft(Values.Quote.precisionDeltaToMoney()) //
                            .setScale(0, RoundingMode.HALF_UP).longValue();

            if (!converter.getTermCurrency().equals(position.security.getCurrencyCode()))
            {
                marketValue = converter.convert(date, Money.of(position.security.getCurrencyCode(), marketValue))
                                .getAmount();
            }

            if (contract.isEmpty())
            {
                valuation += marketValue;
            }
            else
            {
                var c = chains.get(contract.get().chainKey());
                c.legs++;
                c.value += marketValue;
                c.stale |= !priced;
                c.positions.merge(contract.get(), position.shares, Long::sum);
                c.currencyCode = position.security.getCurrencyCode();
            }
        }

        // Option legs that offset each other (a spread, a box) must be priced
        // on the same day: one leg's old price next to the others' new ones
        // moves the net value by far more than it really moved. Until every
        // leg has a price again, the chain keeps its value, unless it traded.
        //
        // A chain whose open legs form box spreads is a loan (or a deposit):
        // its value at expiry is fixed, so it is valued from the value after
        // the last trade to the value due at expiry instead of from the leg
        // prices, which are often missing or taken at different times.
        Map<String, Long> values = new HashMap<>();
        for (var entry : chains.entrySet())
        {
            var key = entry.getKey();
            var c = entry.getValue();
            if (c.legs == 0)
            {
                boxes.remove(key);
                continue;
            }

            Long previous = chainValues.get(key);
            long value;
            if (c.legs > 1 && OptionChain.isBox(c.positions))
            {
                var box = boxes.get(key);
                if (box == null || c.traded)
                {
                    // on the first date the box is taken at its market value;
                    // later, the value before the trades plus their cash
                    long start;
                    if (!valuedBefore)
                        start = c.value;
                    else if (box != null)
                        start = box.valueAt(date) + c.cash;
                    else
                        start = (previous != null ? previous : 0) + c.cash;
                    box = new BoxValuation(date, start, expiry(c), expiryValue(c, date));
                    boxes.put(key, box);
                }
                value = box.valueAt(date);
            }
            else
            {
                boxes.remove(key);
                value = c.legs > 1 && c.stale && !c.traded && previous != null ? previous : c.value;
            }
            values.put(key, value);
            valuation += value;
        }
        chainValues = values;
        valuedBefore = true;

        return valuation;
    }

    private static LocalDate expiry(ChainValuation chain)
    {
        var expiry = chain.positions.keySet().iterator().next().expiry();
        return LocalDate.of(2000 + Integer.parseInt(expiry.substring(0, 2)), Integer.parseInt(expiry.substring(2, 4)),
                        Integer.parseInt(expiry.substring(4, 6)));
    }

    /**
     * Returns the value of a box at expiry: at every strike the call and the
     * put form a synthetic future worth (price - strike) per share, and the
     * prices cancel out, leaving -(shares of the calls x strike).
     */
    private long expiryValue(ChainValuation chain, LocalDate date)
    {
        var value = BigDecimal.ZERO;
        for (var entry : chain.positions.entrySet())
        {
            if (entry.getKey().right() != 'C')
                continue;
            value = value.subtract(BigDecimal.valueOf(entry.getValue()).movePointLeft(Values.Share.precision())
                            .multiply(entry.getKey().strike()));
        }
        long amount = value.movePointRight(Values.Amount.precision()).setScale(0, RoundingMode.HALF_UP).longValue();
        if (!converter.getTermCurrency().equals(chain.currencyCode))
            amount = converter.convert(date, Money.of(chain.currencyCode, amount)).getAmount();
        return amount;
    }

    private void applyPortfolioTransactions(LocalDate date)
    {
        while (portfolioTransactionIndex < portfolioTransactions.size())
        {
            var tx = portfolioTransactions.get(portfolioTransactionIndex);

            if (!tx.getDateTime().toLocalDate().isAfter(date))
            {
                var s = securities.computeIfAbsent(tx.getSecurity(), SecurityPosition::new);
                s.transactions.add(tx);

                if (tx.getType().isPurchase())
                    s.shares += tx.getShares();
                else
                    s.shares -= tx.getShares();

                portfolioTransactionIndex++;
            }
            else
            {
                break;
            }
        }
    }

    private void applyAccountTransactions(LocalDate date)
    {
        for (AccountPosition account : accounts)
        {
            while (account.index < account.transactions.size())
            {
                var tx = account.transactions.get(account.index);

                if (!tx.getDateTime().toLocalDate().isAfter(date))
                {
                    if (tx.getType().isCredit())
                        account.funds += tx.getAmount();
                    else
                        account.funds -= tx.getAmount();

                    account.index++;
                }
                else
                {
                    break;
                }
            }
        }
    }
}
