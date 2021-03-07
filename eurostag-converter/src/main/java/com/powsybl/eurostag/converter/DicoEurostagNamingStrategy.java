/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * Copyright (c) 2017, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.powsybl.eurostag.converter;

import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.powsybl.eurostag.model.EsgException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.it>
 *
 * Creates Eurostag identifiers based on an explicit mapping defined in a CSV-like file (first line is skipped):
 *   IIDM_ID;EUROSTAG_ID
 *
 * Falls back on a default strategy if no explicit correspondance is found: see {@link CutEurostagNamingStrategy}.
 */
public class DicoEurostagNamingStrategy implements EurostagNamingStrategy {

    private static final Logger LOGGER = LoggerFactory.getLogger(DicoEurostagNamingStrategy.class);

    private final BiMap<String, String> dicoMap = HashBiMap.create();

    private final CutEurostagNamingStrategy defaultStrategy;

    private static class DicoCsvReader {

        private static final String SEPARATOR = ";";

        public static List<List<String>> readDicoMappings(Path dicoFile) {
            try (BufferedReader reader = Files.newBufferedReader(dicoFile, StandardCharsets.UTF_8)) {
                return reader.lines()
                        .skip(1)
                        .map(line -> Arrays.asList(line.split(SEPARATOR)))
                        .collect(Collectors.toList());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

    }

    public DicoEurostagNamingStrategy(Path dicoFile) {
        if ((dicoFile == null) || (!Files.isRegularFile(dicoFile))) {
            String errMsg = "csv file does not exist or is not valid: " + dicoFile;
            LOGGER.error(errMsg);
            throw new EsgException(errMsg);
        } else {
            LOGGER.debug("reading iidm-esgid mapping from csv file {}", dicoFile);
            // Note: csv files's first line is skipped, it is expected to be a header line
            List<List<String>> dicoMappings = DicoCsvReader.readDicoMappings(dicoFile);

            int count = 1;
            for (List<String> row : dicoMappings) {
                count++;
                String iidmId = row.get(0).trim();
                String esgId = row.get(1).trim();
                if (esgId.length() > NameType.GENERATOR.getLength()) {
                    LOGGER.warn("Skipping mapping iidmId: {}, esgId: {}. esgId's length > {}. Line {} in {}", iidmId, esgId, NameType.GENERATOR.getLength(), count, dicoFile.toString());
                    continue;
                }
                if ("".equals(iidmId) || "".equals(esgId)) {
                    String errMsg = "either iidmId or esgId or both are empty strings. Line " + count + " in " + dicoFile.toString();
                    LOGGER.error(errMsg);
                    throw new EsgException(errMsg);
                }
                if (dicoMap.containsKey(esgId)) {
                    String errMsg = "esgId: " + esgId + " already mapped.";
                    LOGGER.error(errMsg);
                    throw new EsgException(errMsg);
                }
                dicoMap.put(iidmId, esgId);
            }
            defaultStrategy = new CutEurostagNamingStrategy(new HashSet<>(dicoMap.values()));
        }
    }

    @Override
    public void fillDictionary(EurostagDictionary dictionary, NameType nameType, Set<String> iidmIds) {
        //partition the iidmIds set in two: tiidms with a dico mapping and iidms without a dico mapping
        Map<Boolean, List<String>> dicoPartioned =
                iidmIds.stream().collect(Collectors.partitioningBy(dicoMap::containsKey));

        //first process the entry that are in the dico mapping
        dicoPartioned.get(true).forEach(iidmId -> {
            if (!dictionary.iidmIdExists(iidmId)) {
                String esgId = dicoMap.get(iidmId);
                LOGGER.debug("dico mapping found for iidmId: '{}'; esgId: '{}'", iidmId, esgId);
                dictionary.add(iidmId, esgId);
            }
        });

        //then process the entry that aren't, with the default strategy
        if (!dicoPartioned.get(false).isEmpty()) {
            String notFoundMsgTemplate = "dico mapping not found for iidmId ids: {}";
            List<String> notInMapping = dicoPartioned.get(false);
            if (nameType == NameType.NODE) {
                LOGGER.info(notFoundMsgTemplate, notInMapping);
            } else {
                LOGGER.warn(notFoundMsgTemplate, notInMapping);
            }
            defaultStrategy.fillDictionary(dictionary, nameType, new HashSet<>(notInMapping));
        }
    }
}

