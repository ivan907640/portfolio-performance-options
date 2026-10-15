package name.abuchen.portfolio.snapshot;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.number.IsCloseTo.closeTo;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;

import org.junit.Before;
import org.junit.Test;

import name.abuchen.portfolio.junit.AccountBuilder;
import name.abuchen.portfolio.junit.PortfolioBuilder;
import name.abuchen.portfolio.junit.SecurityBuilder;
import name.abuchen.portfolio.junit.TestCurrencyConverter;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.util.Interval;

@SuppressWarnings("nls")
public class PerformanceCalendarTest
{
    private PerformanceCalendar calendar;

    @Before
    public void setUp()
    {
        var client = new Client();
        var security = new SecurityBuilder() //
                        .addPrice("2026-01-02", Values.Quote.factorize(100)) //
                        .addPrice("2026-01-05", Values.Quote.factorize(110)) //
                        .addPrice("2026-01-06", Values.Quote.factorize(99)) //
                        .addPrice("2026-02-02", Values.Quote.factorize(120)) //
                        .addTo(client);
        var account = new AccountBuilder() //
                        .deposit_("2026-01-02", Values.Amount.factorize(1000)) //
                        .deposit_("2026-01-06", Values.Amount.factorize(500)) //
                        .addTo(client);
        new PortfolioBuilder(account) //
                        .buy(security, "2026-01-02", Values.Share.factorize(10), Values.Amount.factorize(1000)) //
                        .addTo(client);

        var index = PerformanceIndex.forClient(client, new TestCurrencyConverter(),
                        Interval.of(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-02-28")), new ArrayList<>());
        calendar = new PerformanceCalendar(index);
    }

    @Test
    public void testDays()
    {
        // nothing held yet
        assertThat(calendar.day(LocalDate.parse("2026-01-01")).hasData(), is(false));

        // the deposit and the purchase are not a gain
        var opening = calendar.day(LocalDate.parse("2026-01-02"));
        assertThat(opening.hasData(), is(true));
        assertThat(opening.gain(), is(0L));

        var monday = calendar.day(LocalDate.parse("2026-01-05"));
        assertThat(monday.gain(), is(Values.Amount.factorize(100)));
        assertThat(monday.rateOfReturn(), closeTo(0.1, 1e-9));

        // 1,100 + a 500 deposit at the start of the day, 1,490 at the end
        var tuesday = calendar.day(LocalDate.parse("2026-01-06"));
        assertThat(tuesday.gain(), is(Values.Amount.factorize(-110)));
        assertThat(tuesday.rateOfReturn(), closeTo(1490d / 1600 - 1, 1e-9));

        assertThat(calendar.getFirstDate().orElseThrow(), is(LocalDate.parse("2026-01-02")));
    }

    @Test
    public void testMonthsAndYearsChainTheDailyReturns()
    {
        var january = calendar.month(YearMonth.of(2026, 1));
        assertThat(january.gain(), is(Values.Amount.factorize(-10)));
        assertThat(january.rateOfReturn(), closeTo(1.1 * (1490d / 1600) - 1, 1e-9));

        var february = calendar.month(YearMonth.of(2026, 2));
        assertThat(february.gain(), is(Values.Amount.factorize(210)));
        assertThat(february.rateOfReturn(), closeTo(1700d / 1490 - 1, 1e-9));

        var year = calendar.year(2026);
        assertThat(year.gain(), is(Values.Amount.factorize(200)));
        assertThat(year.rateOfReturn(), closeTo((1 + january.rateOfReturn()) * (1 + february.rateOfReturn()) - 1, 1e-9));

        // outside the index
        assertThat(calendar.month(YearMonth.of(2025, 12)).hasData(), is(false));
        assertThat(calendar.year(2027).hasData(), is(false));
    }
}
