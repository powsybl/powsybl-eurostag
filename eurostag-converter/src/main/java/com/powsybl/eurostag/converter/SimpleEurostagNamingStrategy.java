/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * Copyright (c) 2017, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.eurostag.converter;

import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 */
public class SimpleEurostagNamingStrategy implements EurostagNamingStrategy {

    private final Map<NameType, AtomicLong> index = new EnumMap<>(NameType.class);

    public SimpleEurostagNamingStrategy() {
        for (NameType nameType : NameType.values()) {
            index.put(nameType, new AtomicLong());
        }
    }

    private String newEsgId(NameType nameType) {
        char code;
        switch (nameType) {
            case NODE:
                code = 'N';
                break;
            case GENERATOR:
                code = 'G';
                break;
            case LOAD:
                code = 'L';
                break;
            case BANK:
                code = 'B';
                break;
            default:
                throw new AssertionError("Unexpected NameType: " + nameType);

        }
        return createIndexedName(code, index.get(nameType).getAndIncrement(), nameType.getLength());
    }

    @Override
    public void fillDictionary(EurostagDictionary dictionary, NameType nameType, Set<String> iidmIds) {
        for (String iidmId : iidmIds) {
            if (!dictionary.iidmIdExists(iidmId)) {
                String esgId;
                while (dictionary.esgIdExists(esgId = newEsgId(nameType))) {
                    // nothing
                }
                dictionary.add(iidmId, esgId);
            }
        }
    }

    private static String createIndexedName(char code, long index, int maxSize) {
        int number = maxSize - 1;
        String pattern = "%s%0" + number + "d";
        return String.format(pattern, code, index);
    }
}
