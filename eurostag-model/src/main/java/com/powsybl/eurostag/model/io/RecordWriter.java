/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.eurostag.model.io;

import com.powsybl.eurostag.model.EsgException;

import java.io.IOException;
import java.io.Writer;
import java.util.Locale;

/**
 *
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class RecordWriter {

    private static final String NEW_LINE = System.getProperty("line.separator");

    private static final Locale LOCALE = new Locale("en", "US");

    public enum Alignment {
        RIGHT,
        LEFT
    }

    private final Writer writer;

    private int mCurrentLinePos = 1;

    public RecordWriter(Writer writer) {
        this.writer = writer;
    }

    private String format(double aValue, int digit) {
        String val = Double.isNaN(aValue) ? ""   :
                     //...null value will be replaced by "0."
                     aValue == 0.       ? "0." :
                     //...23.0000 will be replaced by "23."
                     aValue % 1 == 0.   ? String.format(LOCALE, "%d.", (int) aValue)
                     //...format double on n digit (right justification)
                     : String.format(LOCALE, "%-" + digit + "f", aValue);

        //...truncate the string if the length is greater than digit+1
        if (val.length() > digit + 1) {
            val = val.substring(0, digit + 1);
        }

        if (val.contains(".")) {
            while (val.endsWith("0")) {
                val = val.substring(0, val.length() - 1);
            }
        }

        return val;
    }

    public void addValue(double aValue, int aColStart, int aColEnd) throws IOException {
        String key = format(aValue, aColEnd - aColStart);
        this.addValue(key, aColStart, aColEnd, Alignment.RIGHT);
    }

    public void addValue(int aValue, int aColStart, int aColEnd) throws IOException {
        String key = Integer.toString(aValue);
        this.addValue(key, aColStart, aColEnd, Alignment.RIGHT);
    }

    public void addValue(String aKey, int aColStart, int aColEnd) throws IOException {
        this.addValue(aKey, aColStart, aColEnd, Alignment.LEFT);
    }

    public void addValue(char aKey, int aColStart) throws IOException {
        this.addValue(Character.toString(aKey), aColStart, aColStart, Alignment.LEFT);
    }

    public void addValue(char aKey, int aColStart, int aColEnd) throws IOException {
        this.addValue(Character.toString(aKey), aColStart, aColEnd, Alignment.LEFT);
    }

    public void addValue(String aKey, int aColStart) throws IOException {
        this.addValue(aKey, aColStart, aColStart + aKey.length() - 1);
    }

    public void addValue(String aKey, int aColStart, int aColEnd, Alignment alignment) throws IOException {
        if (aColEnd < aColStart) {
            throw new EsgException("Bad record encoding for " + aKey);
        }
        int size = 1 + aColEnd - aColStart;

        //...add blank before the next value
        if (aColStart > mCurrentLinePos) {
            int blanknumber = aColStart - mCurrentLinePos;
            writer.append(String.format(LOCALE, "%" + blanknumber + "s", ""));
            mCurrentLinePos = aColStart;
        }
        mCurrentLinePos += size;

        if (alignment == Alignment.LEFT) {
            writer.append(String.format(LOCALE, "%-" + size + "s", aKey));
        } else {
            writer.append(String.format(LOCALE, "%" + size + "s", aKey));
        }
    }

    /**
     * Add a new line at the end of the current record line
     */
    public void addNewLine() throws IOException {
        mCurrentLinePos = 1;
        writer.append(NEW_LINE);
    }
}
