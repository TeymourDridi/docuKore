package com.docukore.cabinet.patient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BirthDateTest
{
    @Test
    void unknownHasNoFabricatedComponents()
    {
        BirthDate birth = BirthDate.unknown();
        assertAll(
            () -> assertEquals(BirthPrecision.UNKNOWN, birth.precision()),
            () -> assertNull(birth.year()),
            () -> assertNull(birth.month()),
            () -> assertNull(birth.day()),
            () -> assertEquals(new BirthDate(null, null, null, BirthPrecision.UNKNOWN), birth)
        );
    }

    @ParameterizedTest
    @CsvSource(value = {
        "COMPLETE_DATE, 2000, 2, 29",
        "COMPLETE_DATE, 2400, 2, 29",
        "COMPLETE_DATE, 1900, 2, 28",
        "COMPLETE_DATE, 1, 1, 1",
        "COMPLETE_DATE, 2147483647, 12, 31",
        "YEAR_MONTH, 1899, 3, NULL",
        "YEAR_MONTH, 2500, 12, NULL",
        "YEAR_ONLY, 1, NULL, NULL",
        "YEAR_ONLY, 1899, NULL, NULL",
        "YEAR_ONLY, 2500, NULL, NULL",
        "UNKNOWN, NULL, NULL, NULL"
    }, nullValues = "NULL")
    void acceptsConsistentComponentsWithoutInventingMinimumYearOrFutureDatePolicy(
        BirthPrecision precision, Integer year, Integer month, Integer day)
    {
        BirthDate birth = new BirthDate(year, month, day, precision);
        assertAll(
            () -> assertEquals(precision, birth.precision()),
            () -> assertEquals(year, birth.year()),
            () -> assertEquals(month, birth.month()),
            () -> assertEquals(day, birth.day())
        );
    }

    @ParameterizedTest
    @CsvSource(value = {
        "COMPLETE_DATE, 1900, 2, 29",
        "COMPLETE_DATE, 2100, 2, 29",
        "COMPLETE_DATE, 2001, 2, 29",
        "COMPLETE_DATE, 2000, 4, 31",
        "COMPLETE_DATE, 2000, 6, 31",
        "COMPLETE_DATE, 2000, 13, 1",
        "COMPLETE_DATE, 2000, 0, 1",
        "COMPLETE_DATE, 2000, 1, 0",
        "COMPLETE_DATE, 2000, 1, 32",
        "COMPLETE_DATE, 0, 1, 1",
        "COMPLETE_DATE, -1, 1, 1",
        "COMPLETE_DATE, NULL, 1, 1",
        "COMPLETE_DATE, 2000, NULL, 1",
        "COMPLETE_DATE, 2000, 1, NULL",
        "YEAR_MONTH, NULL, 1, NULL",
        "YEAR_MONTH, 2000, NULL, NULL",
        "YEAR_MONTH, 2000, 0, NULL",
        "YEAR_MONTH, 2000, 13, NULL",
        "YEAR_MONTH, 0, 1, NULL",
        "YEAR_MONTH, -1, 1, NULL",
        "YEAR_MONTH, 2000, 1, 1",
        "YEAR_ONLY, NULL, NULL, NULL",
        "YEAR_ONLY, 0, NULL, NULL",
        "YEAR_ONLY, -1, NULL, NULL",
        "YEAR_ONLY, 2000, 1, NULL",
        "YEAR_ONLY, 2000, NULL, 1",
        "UNKNOWN, 2000, NULL, NULL",
        "UNKNOWN, NULL, 1, NULL",
        "UNKNOWN, NULL, NULL, 1"
    }, nullValues = "NULL")
    void rejectsInconsistentPrecisionAndInvalidCalendarDates(
        BirthPrecision precision, Integer year, Integer month, Integer day)
    {
        assertThrows(IllegalArgumentException.class, () -> new BirthDate(year, month, day, precision));
    }

    @Test
    void requiresExplicitPrecisionEvenWhenAllComponentsAreAbsent()
    {
        assertThrows(NullPointerException.class, () -> new BirthDate(null, null, null, null));
    }
}
