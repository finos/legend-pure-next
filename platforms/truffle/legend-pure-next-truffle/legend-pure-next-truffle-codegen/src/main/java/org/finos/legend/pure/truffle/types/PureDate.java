package org.finos.legend.pure.truffle.types;

/**
 * Abstract base for Pure date types. Concrete subtypes:
 * <ul>
 *   <li>{@link StrictDate} — full date (yyyy-MM-dd)</li>
 *   <li>{@link DateTime} — date with time (yyyy-MM-ddTHH:mm:ss...)</li>
 * </ul>
 * A plain {@code PureDate} (via {@link #of}) represents a partial date (year or year-month).
 * Use {@code instanceof StrictDate} / {@code instanceof DateTime} for type dispatch.
 */
public sealed class PureDate permits PureDate.StrictDate, PureDate.DateTime, PureDate.PartialDate
{
    private final String dateString;

    protected PureDate(String dateString)
    {
        this.dateString = dateString;
    }

    public String dateString()
    {
        return dateString;
    }

    @Override
    public String toString()
    {
        return dateString;
    }

    @Override
    public boolean equals(Object o)
    {
        return o instanceof PureDate pd && dateString.equals(pd.dateString);
    }

    @Override
    public int hashCode()
    {
        return dateString.hashCode();
    }

    /** Create the appropriate PureDate subtype from a type name. */
    public static PureDate of(String dateString, String typeName)
    {
        return switch (typeName)
        {
            case "StrictDate" -> new StrictDate(dateString);
            case "DateTime" -> new DateTime(dateString);
            default -> new PartialDate(dateString);
        };
    }

    /** yyyy-MM-dd */
    public static final class StrictDate extends PureDate
    {
        public StrictDate(String dateString)
        {
            super(dateString);
        }
    }

    /** yyyy-MM-ddTHH:mm:ss... */
    public static final class DateTime extends PureDate
    {
        public DateTime(String dateString)
        {
            super(dateString);
        }
    }

    /** Year-only or year-month (partial date) */
    public static final class PartialDate extends PureDate
    {
        public PartialDate(String dateString)
        {
            super(dateString);
        }
    }
}
