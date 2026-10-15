package name.abuchen.portfolio.ui.views.calendar;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.eclipse.jface.action.IAction;
import org.eclipse.jface.action.IMenuManager;
import org.eclipse.jface.action.MenuManager;
import org.eclipse.jface.action.Separator;
import org.eclipse.jface.action.ToolBarManager;
import org.eclipse.jface.layout.GridDataFactory;
import org.eclipse.jface.layout.GridLayoutFactory;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;

import name.abuchen.portfolio.model.Classification;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.model.Security;
import name.abuchen.portfolio.model.Taxonomy;
import name.abuchen.portfolio.model.Transaction;
import name.abuchen.portfolio.money.CurrencyConverterImpl;
import name.abuchen.portfolio.money.ExchangeRateProviderFactory;
import name.abuchen.portfolio.money.Values;
import name.abuchen.portfolio.snapshot.OptionChain;
import name.abuchen.portfolio.snapshot.PerformanceCalendar;
import name.abuchen.portfolio.snapshot.PerformanceCalendar.Period;
import name.abuchen.portfolio.snapshot.PerformanceIndex;
import name.abuchen.portfolio.snapshot.filter.ClientClassificationFilter;
import name.abuchen.portfolio.snapshot.filter.ClientSecurityFilter;
import name.abuchen.portfolio.ui.editor.AbstractFinanceView;
import name.abuchen.portfolio.ui.util.ClientFilterDropDown;
import name.abuchen.portfolio.ui.util.ColorGradient;
import name.abuchen.portfolio.ui.util.Colors;
import name.abuchen.portfolio.ui.util.DropDown;
import name.abuchen.portfolio.ui.util.SimpleAction;
import name.abuchen.portfolio.util.Interval;

/**
 * Gain and true time-weighted return per trading day (one month as a
 * calendar), per month (one year) or per year, for the whole client, a filter,
 * a taxonomy category or an option chain (e.g. box spreads).
 */
public class PerformanceCalendarView extends AbstractFinanceView
{
    private enum Mode
    {
        DAY, MONTH, YEAR
    }

    private enum Show
    {
        AMOUNT, PERCENTAGE, BOTH
    }

    private static final String PREF_PREFIX = PerformanceCalendarView.class.getSimpleName();
    private static final String PREF_MODE = PREF_PREFIX + "-mode"; //$NON-NLS-1$
    private static final String PREF_SHOW = PREF_PREFIX + "-show"; //$NON-NLS-1$
    private static final String PREF_GROUP = PREF_PREFIX + "-group"; //$NON-NLS-1$
    private static final String CATEGORY_PREFIX = "category:"; //$NON-NLS-1$
    private static final String CHAIN_PREFIX = "options:"; //$NON-NLS-1$

    private static final DayOfWeek[] TRADING_DAYS = { DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                    DayOfWeek.THURSDAY, DayOfWeek.FRIDAY };

    @Inject
    private ExchangeRateProviderFactory factory;

    private ClientFilterDropDown clientFilter;
    private DropDown groupDropDown;
    private DropDown modeDropDown;
    private DropDown showDropDown;

    /** a taxonomy category, or null */
    private Classification category;
    /** the key of an option chain, or null */
    private String chain;

    private Mode mode = Mode.DAY;
    private Show show = Show.BOTH;
    private YearMonth position = YearMonth.now();

    private PerformanceCalendar calendar;

    private Composite container;
    private Label title;
    private Label summary;
    private Label note;
    private Button previous;
    private Button next;
    private Composite grid;

    @Override
    protected String getDefaultTitle()
    {
        return Messages.LabelCalendar;
    }

    @Override
    protected void addButtons(ToolBarManager toolBar)
    {
        mode = readEnum(PREF_MODE, Mode.class, Mode.DAY);
        show = readEnum(PREF_SHOW, Show.class, Show.BOTH);
        readGroup();

        clientFilter = new ClientFilterDropDown(getClient(), getPreferenceStore(), PREF_PREFIX,
                        filter -> notifyModelUpdated());
        toolBar.add(clientFilter);

        groupDropDown = new DropDown(groupLabel(), this::groupMenu);
        toolBar.add(groupDropDown);

        modeDropDown = new DropDown(modeLabel(mode), manager -> {
            for (var m : Mode.values())
                manager.add(checked(modeLabel(m), m == mode, () -> setMode(m)));
        });
        toolBar.add(modeDropDown);

        showDropDown = new DropDown(showLabel(show), manager -> {
            for (var s : Show.values())
                manager.add(checked(showLabel(s), s == show, () -> {
                    show = s;
                    getPreferenceStore().setValue(PREF_SHOW, s.name());
                    showDropDown.setLabel(showLabel(s));
                    render();
                }));
        });
        toolBar.add(showDropDown);
    }

    @Override
    protected Control createBody(Composite parent)
    {
        container = new Composite(parent, SWT.NONE);
        container.setBackground(Colors.theme().defaultBackground());
        GridLayoutFactory.fillDefaults().margins(10, 10).spacing(5, 8).applyTo(container);

        var header = new Composite(container, SWT.NONE);
        header.setBackground(container.getBackground());
        GridLayoutFactory.fillDefaults().numColumns(5).spacing(8, 0).applyTo(header);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(header);

        previous = new Button(header, SWT.PUSH);
        previous.setText("<"); //$NON-NLS-1$
        previous.setToolTipText(Messages.LabelPrevious);
        previous.addListener(SWT.Selection, e -> move(-1));

        title = new Label(header, SWT.NONE);
        title.setBackground(container.getBackground());
        var titleFont = title.getFont().getFontData()[0];
        var bigFont = new Font(title.getDisplay(), titleFont.getName(), titleFont.getHeight() + 3, SWT.BOLD);
        title.setFont(bigFont);
        title.addDisposeListener(e -> bigFont.dispose());
        GridDataFactory.fillDefaults().align(SWT.BEGINNING, SWT.CENTER).hint(160, SWT.DEFAULT).applyTo(title);

        next = new Button(header, SWT.PUSH);
        next.setText(">"); //$NON-NLS-1$
        next.setToolTipText(Messages.LabelNext);
        next.addListener(SWT.Selection, e -> move(1));

        var today = new Button(header, SWT.PUSH);
        today.setText(Messages.LabelToday);
        today.addListener(SWT.Selection, e -> {
            position = YearMonth.now();
            render();
        });

        summary = new Label(header, SWT.NONE);
        summary.setBackground(container.getBackground());
        GridDataFactory.fillDefaults().grab(true, false).align(SWT.BEGINNING, SWT.CENTER).indent(20, 0)
                        .applyTo(summary);

        note = new Label(container, SWT.WRAP);
        note.setBackground(container.getBackground());
        note.setForeground(Colors.theme().grayForeground());
        GridDataFactory.fillDefaults().grab(true, false).applyTo(note);

        grid = new Composite(container, SWT.NONE);
        grid.setBackground(container.getBackground());
        GridDataFactory.fillDefaults().grab(true, true).applyTo(grid);

        notifyModelUpdated();
        return container;
    }

    @Override
    public void notifyModelUpdated()
    {
        if (grid == null || grid.isDisposed())
            return;

        Client client = getClient();
        Client filtered;
        if (category != null)
            filtered = new ClientClassificationFilter(category).filter(client);
        else if (chain != null)
            filtered = new ClientSecurityFilter(optionChains().getOrDefault(chain, List.of()).toArray(new Security[0]))
                            .filter(client);
        else
            filtered = clientFilter.getSelectedFilter().filter(client);

        var first = Stream.concat( //
                        filtered.getAccounts().stream().flatMap(a -> a.getTransactions().stream()),
                        filtered.getPortfolios().stream().flatMap(p -> p.getTransactions().stream()))
                        .map(Transaction::getDateTime).map(d -> d.toLocalDate()).min(LocalDate::compareTo);

        if (first.isEmpty())
        {
            calendar = null;
        }
        else
        {
            var converter = new CurrencyConverterImpl(factory, client.getBaseCurrency());
            var interval = Interval.of(first.get().minusDays(1), LocalDate.now());
            var index = PerformanceIndex.forClient(filtered, converter, interval, new ArrayList<>());
            calendar = new PerformanceCalendar(index);
        }

        render();
    }

    private void render()
    {
        if (grid == null || grid.isDisposed())
            return;

        container.setRedraw(false);
        try
        {
            for (Control child : grid.getChildren())
                child.dispose();

            boolean grouped = category != null || chain != null;
            note.setText(grouped ? Messages.MsgGroupOverridesFilter : ""); //$NON-NLS-1$
            ((GridData) note.getLayoutData()).exclude = !grouped;
            note.setVisible(grouped);

            previous.setEnabled(mode != Mode.YEAR);
            next.setEnabled(mode != Mode.YEAR);

            switch (mode)
            {
                case DAY -> renderDays();
                case MONTH -> renderMonths();
                case YEAR -> renderYears();
                default -> throw new IllegalStateException();
            }

            container.layout(true, true);
        }
        finally
        {
            container.setRedraw(true);
        }
    }

    private void renderDays()
    {
        title.setText(position.getMonth().getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " //$NON-NLS-1$
                        + position.getYear());

        GridLayoutFactory.fillDefaults().numColumns(TRADING_DAYS.length).equalWidth(true).spacing(3, 3)
                        .applyTo(grid);
        for (var day : TRADING_DAYS)
            header(day.getDisplayName(TextStyle.SHORT, Locale.getDefault()));

        var firstOfMonth = position.atDay(1);
        var lastOfMonth = position.atEndOfMonth();

        List<Period> days = new ArrayList<>();
        for (var d = firstOfMonth; !d.isAfter(lastOfMonth); d = d.plusDays(1))
            if (isTradingDay(d))
                days.add(period(d, d));
        var coloring = new Coloring(days, 0.01);

        var monday = firstOfMonth.minusDays(firstOfMonth.getDayOfWeek().getValue() - 1L);
        for (var week = monday; !week.isAfter(lastOfMonth); week = week.plusWeeks(1))
        {
            // skip a week whose trading days all belong to another month
            var friday = week.plusDays(4);
            if (friday.isBefore(firstOfMonth) || week.isAfter(lastOfMonth))
                continue;

            for (int ii = 0; ii < TRADING_DAYS.length; ii++)
            {
                var date = week.plusDays(ii);
                if (date.getMonth() != position.getMonth())
                    cell(null, null, coloring);
                else
                    cell(String.valueOf(date.getDayOfMonth()), period(date, date), coloring);
            }
        }

        setSummary(period(firstOfMonth, lastOfMonth));
    }

    private void renderMonths()
    {
        title.setText(String.valueOf(position.getYear()));
        GridLayoutFactory.fillDefaults().numColumns(4).equalWidth(true).spacing(3, 3).applyTo(grid);

        List<Period> months = new ArrayList<>();
        for (var m : Month.values())
            months.add(period(YearMonth.of(position.getYear(), m)));
        var coloring = new Coloring(months, 0.03);

        for (var m : Month.values())
        {
            var month = YearMonth.of(position.getYear(), m);
            var cell = cell(m.getDisplayName(TextStyle.FULL, Locale.getDefault()), period(month), coloring);
            drillDown(cell, () -> {
                position = month;
                setMode(Mode.DAY);
            });
        }

        setSummary(period(LocalDate.of(position.getYear(), 1, 1), LocalDate.of(position.getYear(), 12, 31)));
    }

    private void renderYears()
    {
        GridLayoutFactory.fillDefaults().numColumns(5).equalWidth(true).spacing(3, 3).applyTo(grid);

        var first = calendar != null ? calendar.getFirstDate() : Optional.<LocalDate>empty();
        var last = calendar != null ? calendar.getLastDate() : Optional.<LocalDate>empty();
        if (first.isEmpty() || last.isEmpty())
        {
            title.setText(""); //$NON-NLS-1$
            setSummary(null);
            return;
        }

        int firstYear = first.get().getYear();
        int lastYear = last.get().getYear();
        title.setText(firstYear == lastYear ? String.valueOf(firstYear) : firstYear + " – " + lastYear); //$NON-NLS-1$

        List<Period> years = new ArrayList<>();
        for (int y = firstYear; y <= lastYear; y++)
            years.add(calendar.year(y));
        var coloring = new Coloring(years, 0.10);

        for (int y = firstYear; y <= lastYear; y++)
        {
            int year = y;
            var cell = cell(String.valueOf(y), calendar.year(y), coloring);
            drillDown(cell, () -> {
                position = YearMonth.of(year, position.getMonth());
                setMode(Mode.MONTH);
            });
        }

        setSummary(calendar.period(first.get(), last.get()));
    }

    private static boolean isTradingDay(LocalDate date)
    {
        return date.getDayOfWeek() != DayOfWeek.SATURDAY && date.getDayOfWeek() != DayOfWeek.SUNDAY;
    }

    private Period period(LocalDate first, LocalDate last)
    {
        return calendar != null ? calendar.period(first, last) : new Period(first, last, 0, 0, false);
    }

    private Period period(YearMonth month)
    {
        return period(month.atDay(1), month.atEndOfMonth());
    }

    private void header(String text)
    {
        var label = new Label(grid, SWT.CENTER);
        label.setBackground(grid.getBackground());
        label.setForeground(Colors.theme().grayForeground());
        label.setText(text);
        GridDataFactory.fillDefaults().grab(true, false).applyTo(label);
    }

    private CalendarCell cell(String caption, Period period, Coloring coloring)
    {
        var cell = new CalendarCell(grid);
        GridDataFactory.fillDefaults().grab(true, true).applyTo(cell);

        if (period == null || !period.hasData())
        {
            cell.setContent(caption, null, false);
            if (period != null)
                cell.setToolTipText(Messages.MsgNoData);
            return cell;
        }

        var lines = switch (show)
        {
            case AMOUNT -> new String[] { amount(period.gain(), false) };
            case PERCENTAGE -> new String[] { percentage(period.rateOfReturn()) };
            case BOTH -> new String[] { amount(period.gain(), false), percentage(period.rateOfReturn()) };
            default -> throw new IllegalStateException();
        };
        cell.setContent(caption, coloring.colorOf(period), false, lines);
        cell.setToolTipText(amount(period.gain(), true) + "  " + percentage(period.rateOfReturn())); //$NON-NLS-1$
        return cell;
    }

    private void drillDown(CalendarCell cell, Runnable action)
    {
        cell.setCursor(cell.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
        cell.addListener(SWT.MouseUp, e -> action.run());
    }

    private void setSummary(Period period)
    {
        if (period == null || !period.hasData())
            summary.setText(Messages.MsgNoData);
        else
            summary.setText(Messages.LabelTotal + ": " + amount(period.gain(), true) + " " + getClient().getBaseCurrency() //$NON-NLS-1$ //$NON-NLS-2$
                            + "   " + percentage(period.rateOfReturn())); //$NON-NLS-1$
    }

    private static String amount(long gain, boolean exact)
    {
        var text = exact ? Values.Amount.format(gain) : Values.AmountShort.format(gain);
        return gain > 0 ? "+" + text : text; //$NON-NLS-1$
    }

    private static String percentage(double rateOfReturn)
    {
        var text = Values.Percent2.format(rateOfReturn);
        return rateOfReturn > 0 ? "+" + text : text; //$NON-NLS-1$
    }

    /**
     * Soft tints by rate of return, relative to the largest move on the screen
     * but at least to a typical move of the period (so a quiet month of small
     * moves stays pale). The square root keeps small moves visible, and even
     * the largest move stays a light tint so the numbers remain readable.
     */
    private static final class Coloring
    {
        private static final float MAX_INTENSITY = 0.85f;

        private final ColorGradient gradient = new ColorGradient(Colors.getColor(229, 115, 115),
                        Colors.theme().defaultBackground(), Colors.getColor(102, 187, 106));
        private final double scale;

        private Coloring(List<Period> periods, double typicalMove)
        {
            scale = Math.max(typicalMove, periods.stream().filter(Period::hasData)
                            .mapToDouble(p -> Math.abs(p.rateOfReturn())).max().orElse(0));
        }

        private Color colorOf(Period period)
        {
            double v = period.rateOfReturn();
            if (v == 0)
                return null;
            var share = Math.sqrt(Math.min(1, Math.abs(v) / scale)) * MAX_INTENSITY;
            return gradient.getColorAt((float) (0.5 + Math.signum(v) * 0.5 * share));
        }
    }

    private void move(int step)
    {
        position = mode == Mode.DAY ? position.plusMonths(step) : position.plusYears(step);
        render();
    }

    private void setMode(Mode newMode)
    {
        mode = newMode;
        getPreferenceStore().setValue(PREF_MODE, newMode.name());
        modeDropDown.setLabel(modeLabel(newMode));
        render();
    }

    // groups: taxonomy categories and option chains

    /** Option legs by chain (underlying and expiry), sorted by chain. */
    private Map<String, List<Security>> optionChains()
    {
        Map<String, List<Security>> chains = new TreeMap<>();
        for (Security security : getClient().getSecurities())
            OptionChain.keyOf(security).ifPresent(k -> chains.computeIfAbsent(k, x -> new ArrayList<>()).add(security));
        return chains;
    }

    private String chainLabel(String key, List<Security> legs)
    {
        // all transactions to date give the open positions
        Map<Security, Long> shares = new HashMap<>();
        getClient().getPortfolios().stream().flatMap(p -> p.getTransactions().stream())
                        .filter(t -> legs.contains(t.getSecurity()))
                        .forEach(t -> shares.merge(t.getSecurity(),
                                        t.getType().isPurchase() ? t.getShares() : -t.getShares(), Long::sum));

        Map<OptionChain.Contract, Long> positions = new HashMap<>();
        for (Security leg : legs)
            OptionChain.contractOf(leg).ifPresent(c -> positions.merge(c, shares.getOrDefault(leg, 0L), Long::sum));

        var separator = key.indexOf(' ');
        var underlying = key.substring(0, separator);
        var expiry = LocalDate.parse("20" + key.substring(separator + 1), DateTimeFormatter.BASIC_ISO_DATE); //$NON-NLS-1$
        var kind = OptionChain.isBox(positions) ? Messages.LabelBoxSpreads : Messages.LabelOptions;
        return underlying + " " + expiry.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())) //$NON-NLS-1$ //$NON-NLS-2$
                        + " " + kind + " (" + legs.size() + " " //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
                        + (legs.size() == 1 ? Messages.LabelLeg : Messages.LabelLegs) + ")"; //$NON-NLS-1$
    }

    private void groupMenu(IMenuManager manager)
    {
        manager.add(checked(Messages.LabelNoCategory, category == null && chain == null, () -> setGroup(null, null)));

        var chains = optionChains();
        if (!chains.isEmpty())
        {
            manager.add(new Separator());
            var submenu = new MenuManager(Messages.LabelOptionChains);
            chains.forEach((key, legs) -> submenu
                            .add(checked(chainLabel(key, legs), key.equals(chain), () -> setGroup(CHAIN_PREFIX + key, null))));
            manager.add(submenu);
        }

        if (!getClient().getTaxonomies().isEmpty())
        {
            manager.add(new Separator());
            for (Taxonomy taxonomy : getClient().getTaxonomies())
            {
                var submenu = new MenuManager(taxonomy.getName());
                addClassifications(submenu, taxonomy, taxonomy.getRoot(), ""); //$NON-NLS-1$
                manager.add(submenu);
            }
        }
    }

    private void addClassifications(MenuManager menu, Taxonomy taxonomy, Classification parent, String path)
    {
        for (Classification child : parent.getChildren())
        {
            var label = path + child.getName();
            menu.add(checked(label, child == category,
                            () -> setGroup(CATEGORY_PREFIX + taxonomy.getId() + "/" + child.getId(), child))); //$NON-NLS-1$
            addClassifications(menu, taxonomy, child, label + " › "); //$NON-NLS-1$
        }
    }

    private void setGroup(String preference, Classification classification)
    {
        getPreferenceStore().setValue(PREF_GROUP, preference == null ? "" : preference); //$NON-NLS-1$
        category = classification;
        chain = preference != null && preference.startsWith(CHAIN_PREFIX) ? preference.substring(CHAIN_PREFIX.length())
                        : null;
        groupDropDown.setLabel(groupLabel());
        notifyModelUpdated();
    }

    private void readGroup()
    {
        var value = getPreferenceStore().getString(PREF_GROUP);
        category = null;
        chain = null;

        if (value.startsWith(CHAIN_PREFIX))
        {
            var key = value.substring(CHAIN_PREFIX.length());
            if (optionChains().containsKey(key))
                chain = key;
        }
        else if (value.startsWith(CATEGORY_PREFIX))
        {
            var ids = value.substring(CATEGORY_PREFIX.length());
            int separator = ids.indexOf('/');
            if (separator < 0)
                return;
            var taxonomyId = ids.substring(0, separator);
            var classificationId = ids.substring(separator + 1);
            category = getClient().getTaxonomies().stream().filter(t -> t.getId().equals(taxonomyId)).findAny()
                            .map(t -> t.getClassificationById(classificationId)).orElse(null);
        }
    }

    private String groupLabel()
    {
        if (category != null)
            return category.getName();
        if (chain != null)
            return chainLabel(chain, optionChains().getOrDefault(chain, List.of()));
        return Messages.LabelNoCategory;
    }

    private <E extends Enum<E>> E readEnum(String key, Class<E> type, E fallback)
    {
        try
        {
            var value = getPreferenceStore().getString(key);
            return value.isEmpty() ? fallback : Enum.valueOf(type, value);
        }
        catch (IllegalArgumentException e)
        {
            return fallback;
        }
    }

    private static SimpleAction checked(String label, boolean checked, Runnable runnable)
    {
        var action = new SimpleAction(label, IAction.AS_CHECK_BOX, a -> runnable.run());
        action.setChecked(checked);
        return action;
    }

    private static String modeLabel(Mode mode)
    {
        return switch (mode)
        {
            case DAY -> Messages.LabelDay;
            case MONTH -> Messages.LabelMonth;
            case YEAR -> Messages.LabelYear;
            default -> throw new IllegalStateException();
        };
    }

    private static String showLabel(Show show)
    {
        return switch (show)
        {
            case AMOUNT -> Messages.LabelMoney;
            case PERCENTAGE -> Messages.LabelPercentage;
            case BOTH -> Messages.LabelMoneyAndPercentage;
            default -> throw new IllegalStateException();
        };
    }
}
