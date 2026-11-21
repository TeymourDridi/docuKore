package com.docukore.cabinet.patient;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

import java.time.Month;
import java.time.Year;
import java.util.Objects;

/**
 * Immutable birth information; precision and nullable components are one value.
 * Calendar rules match tenant V7. A future-date policy is not introduced here.
 *
 * @param year      the birth year, or {@code null} if unknown
 * @param month     the birth month, or {@code null} if unknown
 * @param day       the birth day, or {@code null} if unknown
 * @param precision the precision level of the birth date
 */
@Embeddable
public record BirthDate(
    @Column(name = "birth_year") Integer year,
    @Column(name = "birth_month") Integer month,
    @Column(name = "birth_day") Integer day,
    @Column(name = "birth_precision", nullable = false, length = 20)
    @Enumerated(EnumType.STRING) BirthPrecision precision)
{
    public BirthDate
    {
        Objects.requireNonNull(precision, "precision");
        boolean valid = switch (precision)
        {
            case UNKNOWN -> year == null && month == null && day == null;
            case YEAR_ONLY -> validYear(year) && month == null && day == null;
            case YEAR_MONTH -> validYear(year) && validMonth(month) && day == null;
            case COMPLETE_DATE -> validYear(year) && validMonth(month) && day != null
                && day >= 1 && day <= Month.of(month).length(Year.isLeap(year));
        };
        if (!valid)
        {
            throw new IllegalArgumentException("Birth components must match precision and form a valid calendar date");
        }
    }

    public static BirthDate unknown()
    {
        return new BirthDate(null, null, null, BirthPrecision.UNKNOWN);
    }

    private static boolean validYear(Integer year)
    {
        return year != null && year > 0;
    }

    private static boolean validMonth(Integer month)
    {
        return month != null && month >= 1 && month <= 12;
    }
}
