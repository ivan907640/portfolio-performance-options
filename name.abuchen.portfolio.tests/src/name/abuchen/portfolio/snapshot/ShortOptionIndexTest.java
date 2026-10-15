package name.abuchen.portfolio.snapshot;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.number.IsCloseTo.closeTo;
import static org.hamcrest.CoreMatchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.junit.TaxonomyBuilder;
import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.model.Account;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Taxonomy;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.util.Dates;
import name.abuchen.portfolio.util.Interval;

@SuppressWarnings("nls")
public class ShortOptionIndexTest
{
    private final Interval period = Interval.of(LocalDate.parse("2026-01-04"), LocalDate.parse("2026-01-07"));

    private static Security option(Client client, String name, String ticker, Taxonomy taxonomy, double... prices)
    {
        var builder = new SecurityBuilder();
        String[] dates = { "2026-01-05", "2026-01-06", "2026-01-07" };
        for (int ii = 0; ii < prices.length; ii++)
            if (!Double.isNaN(prices[ii]))
                builder.addPrice(dates[ii], Values.Quote.factorize(prices[ii]));
        if (taxonomy != null)
            builder.assign(taxonomy, "spread");
        var security = builder.addTo(client);
        security.setName(name);
        security.setTickerSymbol(ticker);
        return security;
    }

    @Test
    public void testThatAShortPutLosingValueShowsAGain()
    {
        var client = new Client();
        Account account = new AccountBuilder().deposit_("2026-01-05", Values.Amount.factorize(10000)).addTo(client);
        // one contract of 100: sold at 5, then marked at 4 and 3
        var put = option(client, "XYZ 20MAR26 100 P", "XYZ260320P00100000", null, 5, 4, 3);
        new PortfolioBuilder(account).sell(put, "2026-01-05", Values.Share.factorize(100), Values.Amount.factorize(500))
                        .addTo(client);

        List<Exception> warnings = new ArrayList<>();
        var index = PerformanceIndex.forInvestment(client, new TestCurrencyConverter(), put, period, warnings);

        assertTrue(warnings.toString(), warnings.isEmpty());
        assertThat(index.getTotals()[2], is(Values.Amount.factorize(-400)));
        // owed 500, now 400: a gain of 100 on 500 owed, then 100 on 400
        assertThat(index.getDeltaPercentage()[2], closeTo(0.2, 1e-9));
        assertThat(index.getDeltaPercentage()[3], closeTo(0.25, 1e-9));
        assertThat(index.getFinalAccumulatedPercentage(), closeTo(1.2 * 1.25 - 1, 1e-9));
    }

    @Test
    public void testThatSpreadLegsAreValuedOnTheSameDay()
    {
        var client = new Client();
        Taxonomy taxonomy = new TaxonomyBuilder().addClassification("spread").addTo(client);
        Account account = new AccountBuilder().deposit_("2026-01-05", Values.Amount.factorize(10000)).addTo(client);
        var shortPut = option(client, "XYZ 20MAR26 100 P", "XYZ260320P00100000", taxonomy, 5, 4, 3);
        // no price on 6 January
        var longPut = option(client, "XYZ 20MAR26 95 P", "XYZ260320P00095000", taxonomy, 3, Double.NaN, 2);
        new PortfolioBuilder(account) //
                        .sell(shortPut, "2026-01-05", Values.Share.factorize(100), Values.Amount.factorize(500)) //
                        .buy(longPut, "2026-01-05", Values.Share.factorize(100), Values.Amount.factorize(300)) //
                        .addTo(client);

        List<Exception> warnings = new ArrayList<>();
        var index = PerformanceIndex.forClassification(client, new TestCurrencyConverter(),
                        taxonomy.getClassificationById("spread"), period, warnings);

        assertTrue(warnings.toString(), warnings.isEmpty());
        // net -200; on 6 January the long put's price is a day old, so the spread keeps -200
        // instead of pairing it with the short put's new price (-100)
        assertThat(index.getTotals()[1], is(Values.Amount.factorize(-200)));
        assertThat(index.getTotals()[2], is(Values.Amount.factorize(-200)));
        assertThat(index.getTotals()[3], is(Values.Amount.factorize(-100)));
        assertThat(index.getFinalAccumulatedPercentage(), closeTo(0.5, 1e-9));
    }

    @Test
    public void testOptionChainKeys()
    {
        var client = new Client();
        var a = option(client, "SPX 18DEC26 5000 C", "SPX261218C05000000", null);
        var b = option(client, "SPX 18DEC26 7000 P", null, null);
        var c = option(client, "SPXW 18DEC26 6000 C", "SPXW261218C06000000", null);
        var stock = option(client, "BABA", "BABA", null);

        assertThat(OptionChain.keyOf(a).orElseThrow(), is("SPX 261218"));
        assertThat(OptionChain.keyOf(b).orElseThrow(), is("SPX 261218"));
        assertThat(OptionChain.keyOf(c).orElseThrow(), is("SPX 261218"));
        assertTrue(OptionChain.keyOf(stock).isEmpty());
    }

    @Test
    public void testBoxDetection()
    {
        var client = new Client();
        var c100 = OptionChain.contractOf(option(client, "XYZ 20MAR26 100 C", "XYZ260320C00100000", null))
                        .orElseThrow();
        var p100 = OptionChain.contractOf(option(client, "XYZ 20MAR26 100 P", "XYZ260320P00100000", null))
                        .orElseThrow();
        var c110 = OptionChain.contractOf(option(client, "XYZ 20MAR26 110 C", null, null)).orElseThrow();
        var p110 = OptionChain.contractOf(option(client, "XYZ 20MAR26 110 P", null, null)).orElseThrow();

        assertThat(c110.strike(), is(new BigDecimal("110")));
        assertThat(c100.chainKey(), is(c110.chainKey()));

        // long box: synthetic long at 100, synthetic short at 110
        assertTrue(OptionChain.isBox(Map.of(c100, 100L, p100, -100L, c110, -100L, p110, 100L)));
        // a vertical spread is not a box
        assertFalse(OptionChain.isBox(Map.of(c100, 100L, c110, -100L)));
        // closed positions are not a box
        assertFalse(OptionChain.isBox(Map.of(c100, 0L, p100, 0L)));
    }

    @Test
    public void testThatABoxSpreadIsValuedAsALoan()
    {
        var client = new Client();
        Taxonomy taxonomy = new TaxonomyBuilder().addClassification("spread").addTo(client);
        Account account = new AccountBuilder().deposit_("2026-01-05", Values.Amount.factorize(10000)).addTo(client);

        // leg prices that do not fit together; the box ignores them
        var c100 = option(client, "XYZ 20MAR26 100 C", "XYZ260320C00100000", taxonomy, 6, 9, Double.NaN);
        var p100 = option(client, "XYZ 20MAR26 100 P", "XYZ260320P00100000", taxonomy, 2, Double.NaN, 1);
        var c110 = option(client, "XYZ 20MAR26 110 C", "XYZ260320C00110000", taxonomy, 1.2, 0.5, 3);
        var p110 = option(client, "XYZ 20MAR26 110 P", "XYZ260320P00110000", taxonomy, 7, Double.NaN, 4);

        // short box: borrow 980 now, pay 1,000 (strike width 10 x 100) on 20 March
        new PortfolioBuilder(account) //
                        .sell(c100, "2026-01-05", Values.Share.factorize(100), Values.Amount.factorize(600)) //
                        .buy(p100, "2026-01-05", Values.Share.factorize(100), Values.Amount.factorize(200)) //
                        .buy(c110, "2026-01-05", Values.Share.factorize(100), Values.Amount.factorize(120)) //
                        .sell(p110, "2026-01-05", Values.Share.factorize(100), Values.Amount.factorize(700)) //
                        .addTo(client);

        List<Exception> warnings = new ArrayList<>();
        var index = PerformanceIndex.forClassification(client, new TestCurrencyConverter(),
                        taxonomy.getClassificationById("spread"),
                        Interval.of(LocalDate.parse("2026-01-04"), LocalDate.parse("2026-03-31")), warnings);

        assertTrue(warnings.toString(), warnings.isEmpty());
        var totals = index.getTotals();
        var dates = index.getDates();
        assertThat(totals[1], is(Values.Amount.factorize(-980)));
        int expiry = Dates.daysBetween(dates[0], LocalDate.parse("2026-03-20"));
        assertThat(totals[expiry], is(Values.Amount.factorize(-1000)));

        // the cost of borrowing accrues smoothly: a small loss every day
        var delta = index.getDeltaPercentage();
        for (int ii = 2; ii <= expiry; ii++)
        {
            assertTrue(dates[ii] + " " + totals[ii], totals[ii] <= totals[ii - 1] && totals[ii] > totals[1] - 2100);
            assertThat(delta[ii], closeTo(1 - Math.pow(1000d / 980, 1d / (expiry - 1)), 1e-5));
        }
        assertThat(index.getFinalAccumulatedPercentage(), closeTo(-20d / 980, 1e-3));
    }
}
