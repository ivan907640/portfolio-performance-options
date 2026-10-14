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

        /** the option chain (underlying and expiry), if it is an option */
        private Optional<String> chain;

        public SecurityPosition(Security security)
        {
            this.security = security;
            this.transactions = new ArrayList<>();
            this.shares = 0;
        }

        private Optional<String> chain()
        {
            if (chain == null)
                chain = OptionChain.keyOf(security);
            return chain;
        }
    }

    /** the legs of one option chain held on a day */
    private static class ChainValuation
    {
        private int legs;
        private long value;
        private boolean stale;
        private boolean traded;
    }

    private final CurrencyConverter converter;

    private final List<AccountPosition> accounts;

    private final Map<Security, SecurityPosition> securities = new HashMap<>();
    private final List<PortfolioTransaction> portfolioTransactions;
    private int portfolioTransactionIndex = 0;

    /** the value of each option chain on the previous valuation date */
    private Map<String, Long> chainValues = new HashMap<>();

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

            var chain = position.chain();
            if (chain.isEmpty())
            {
                valuation += marketValue;
            }
            else
            {
                var c = chains.computeIfAbsent(chain.get(), k -> new ChainValuation());
                c.legs++;
                c.value += marketValue;
                c.stale |= !priced;
                c.traded |= position.transactions.get(position.transactions.size() - 1).getDateTime().toLocalDate()
                                .equals(date);
            }
        }

        // Option legs that offset each other (a spread, a box) must be priced
        // on the same day: one leg's old price next to the others' new ones
        // moves the net value by far more than it really moved. Until every
        // leg has a price again, the chain keeps its value, unless it traded.
        Map<String, Long> values = new HashMap<>();
        for (var entry : chains.entrySet())
        {
            var c = entry.getValue();
            Long previous = chainValues.get(entry.getKey());
            long value = c.legs > 1 && c.stale && !c.traded && previous != null ? previous : c.value;
            values.put(entry.getKey(), value);
            valuation += value;
        }
        chainValues = values;

        return valuation;
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
