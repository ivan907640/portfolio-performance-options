package name.abuchen.portfolio.ui.views.calendar;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.Canvas;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;

import name.abuchen.portfolio.ui.util.Colors;

/**
 * A cell of the calendar: a caption (the day, month or year) in the top left
 * corner and up to two value lines in the middle, on a colored background.
 */
/* package */ class CalendarCell extends Canvas
{
    private static final int MARGIN = 4;

    private String caption;
    private String[] lines = new String[0];
    private Color backdrop;
    private boolean bold;

    /* package */ CalendarCell(Composite parent)
    {
        super(parent, SWT.DOUBLE_BUFFERED);
        addListener(SWT.Paint, this::paint);
    }

    /* package */ void setContent(String caption, Color backdrop, boolean bold, String... lines)
    {
        this.caption = caption;
        this.backdrop = backdrop;
        this.bold = bold;
        this.lines = lines;
        redraw();
    }

    @Override
    public Point computeSize(int wHint, int hHint, boolean changed)
    {
        var gc = new GC(this);
        var lineHeight = gc.getFontMetrics().getHeight();
        gc.dispose();
        return new Point(wHint == SWT.DEFAULT ? 90 : wHint, hHint == SWT.DEFAULT ? lineHeight * 3 + 3 * MARGIN : hHint);
    }

    private void paint(Event e)
    {
        Rectangle bounds = getClientArea();
        var gc = e.gc;

        Color background = backdrop != null ? backdrop : getParent().getBackground();
        gc.setBackground(background);
        gc.fillRectangle(bounds);
        gc.setForeground(Colors.theme().grayForeground());
        gc.drawRectangle(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1);

        var foreground = backdrop != null ? Colors.getTextColor(backdrop) : Colors.theme().defaultForeground();
        var lineHeight = gc.getFontMetrics().getHeight();

        if (caption != null)
        {
            gc.setForeground(backdrop != null ? foreground : Colors.theme().grayForeground());
            gc.drawText(caption, bounds.x + MARGIN, bounds.y + MARGIN / 2, true);
        }

        gc.setForeground(foreground);
        var font = getFont();
        Font boldFont = null;
        if (bold)
        {
            var data = font.getFontData()[0];
            boldFont = new Font(getDisplay(), data.getName(), data.getHeight(), SWT.BOLD);
            gc.setFont(boldFont);
        }

        int top = bounds.y + MARGIN / 2 + lineHeight;
        int available = bounds.height - (top - bounds.y);
        int y = top + Math.max(0, (available - lines.length * lineHeight) / 2);
        for (String line : lines)
        {
            var extent = gc.stringExtent(line);
            gc.drawText(line, bounds.x + Math.max(MARGIN, (bounds.width - extent.x) / 2), y, true);
            y += lineHeight;
        }

        if (boldFont != null)
            boldFont.dispose();
    }
}
