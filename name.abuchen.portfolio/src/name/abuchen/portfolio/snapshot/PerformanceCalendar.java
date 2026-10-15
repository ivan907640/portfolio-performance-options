package name.abuchen.portfolio.snapshot;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;

import name.abuchen.portfolio.util.Dates;

/**
 * Gain and true time-weighted rate of return of a performance index per day,
 * month or year, e.g. for a calendar. The gain of a period is the change in
 * value without deposits and removals; its rate of return chains the daily
 * returns of the index, as the performance heat maps do.
 */
public final class PerformanceCalendar
{
    /**
     * A period of the calendar. {@code hasData} is false if nothing was held
     * on any day of the period.
     */
    public record Period(LocalDate first, LocalDate last, long gain, double rateOfReturn, boolean hasData)
    {
    }

    private final LocalDate[] dates;
    private final long[] gains;
    private final double[] returns;
    private final boolean[] valued;

    public PerformanceCalendar(PerformanceIndex index)
    {
        dates = index.getDates();
        long[] totals = index.getTotals();
        double[] delta = index.getDeltaPercentage();
        long[] inbound = index.getInboundTransferals();
        long[] outbound = index.getOutboundTransferals();

        gains = new long[dates.length];
        returns = new double[dates.length];
        valued = new boolean[dates.length];

        // the first date of the index is the reference value, not a day of
        // the calendar
        for (int ii = 1; ii < dates.length; ii++)
        {
            gains[ii] = totals[ii] - totals[ii - 1] - inbound[ii] + outbound[ii];
            returns[ii] = delta[ii];
            valued[ii] = totals[ii - 1] != 0 || totals[ii] != 0 || inbound[ii] != 0 || outbound[ii] != 0;
        }
    }

    public Period day(LocalDate date)
    {
        return period(date, date);
    }

    public Period month(YearMonth month)
    {
        return period(month.atDay(1), month.atEndOfMonth());
    }

    public Period year(int year)
    {
        return period(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
    }

    /**
     * Returns the gain and the chained rate of return from the first to the
     * last date, both included.
     */
    public Period period(LocalDate first, LocalDate last)
    {
        long gain = 0;
        double level = 1;
        boolean hasData = false;

        if (dates.length > 1)
        {
            int from = Math.max(1, (int) Dates.daysBetween(dates[0], first));
            int to = Math.min(dates.length - 1, (int) Dates.daysBetween(dates[0], last));

            for (int ii = from; ii <= to; ii++)
            {
                if (!valued[ii])
                    continue;
                hasData = true;
                gain += gains[ii];
                level *= 1 + returns[ii];
            }
        }

        return new Period(first, last, gain, level - 1, hasData);
    }

    /** Returns the first day on which something was held. */
    public Optional<LocalDate> getFirstDate()
    {
        for (int ii = 1; ii < dates.length; ii++)
            if (valued[ii])
                return Optional.of(dates[ii]);
        return Optional.empty();
    }

    /** Returns the last day of the index. */
    public Optional<LocalDate> getLastDate()
    {
        return dates.length > 1 ? Optional.of(dates[dates.length - 1]) : Optional.empty();
    }
}
