package com.docukore.cabinet.patient;

/**
 * Precision level of a patient's date of birth.
 *
 * <p>Medical records may have varying precision for birth dates depending on
 * the source of information and patient knowledge.</p>
 */
public enum BirthPrecision
{
    /**
     * Full date known (day, month, year).
     */
    COMPLETE_DATE,
    /**
     * Year and month known, day unknown.
     */
    YEAR_MONTH,
    /**
     * Only year known.
     */
    YEAR_ONLY,
    /**
     * Birth date completely unknown.
     */
    UNKNOWN
}