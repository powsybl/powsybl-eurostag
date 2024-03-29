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
import com.powsybl.eurostag.model.Esg8charName;
import com.powsybl.eurostag.model.EsgBranchName;
import com.powsybl.eurostag.model.EsgThreeWindingTransformer;
import com.powsybl.eurostag.model.EsgException;
import com.powsybl.iidm.network.*;
import com.powsybl.iidm.network.util.Identifiables;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

/**
 * @author Geoffroy Jamgotchian <geoffroy.jamgotchian at rte-france.com>
 *
 *     Mapping between IIDM identifiers and Eurostage identifiers.
 *     That mapping will rely in particular on a {@link EurostagNamingStrategy}.
 */
public final class EurostagDictionary {

    private static final Logger LOGGER = LoggerFactory.getLogger(EurostagDictionary.class);

    private static final EurostagNamingStrategy NAMING_STRATEGY = new DicoEurostagNamingStrategyFactory().create();

    private final BiMap<String, String> iidmId2esgId;

    private final EurostagEchExportConfig config;

    public static EurostagDictionary create(Network network, BranchParallelIndexes parallelIndexes, EurostagEchExportConfig config, EurostagFakeNodes fakeNodes) {
        EurostagDictionary dictionary = new EurostagDictionary(config);

        fakeNodes.esgIdsAsStream().forEach(esgId -> dictionary.addIfNotExist(esgId, esgId));

        Set<String> busIds = Identifiables.sort(EchUtil.getBuses(network, config)).stream().map(Bus::getId).collect(Collectors.toSet());
        Set<String> loadIds = new LinkedHashSet<>();
        Identifiables.sort(network.getDanglingLines()).forEach(dl -> {
            busIds.add(EchUtil.getBusId(dl));
            loadIds.add(EchUtil.getLoadId(dl));
        });
        Identifiables.sort(network.getLoads()).forEach(l -> loadIds.add(l.getId()));
        Set<String> generatorIds = Identifiables.sort(network.getGenerators()).stream().map(Generator::getId).collect(Collectors.toSet());
        Set<String> shuntIds = Identifiables.sort(network.getShuntCompensators()).stream().map(ShuntCompensator::getId).collect(Collectors.toSet());
        Set<String> svcIds = Identifiables.sort(network.getStaticVarCompensators()).stream().map(StaticVarCompensator::getId).collect(Collectors.toSet());
        Set<String> converterStationsIds = Identifiables.sort(network.getVscConverterStations()).stream().map(VscConverterStation::getId).collect(Collectors.toSet());

        NAMING_STRATEGY.fillDictionary(dictionary, EurostagNamingStrategy.NameType.NODE, busIds);
        NAMING_STRATEGY.fillDictionary(dictionary, EurostagNamingStrategy.NameType.GENERATOR, generatorIds);
        NAMING_STRATEGY.fillDictionary(dictionary, EurostagNamingStrategy.NameType.LOAD, loadIds);
        NAMING_STRATEGY.fillDictionary(dictionary, EurostagNamingStrategy.NameType.BANK, shuntIds);
        NAMING_STRATEGY.fillDictionary(dictionary, EurostagNamingStrategy.NameType.SVC, svcIds);
        NAMING_STRATEGY.fillDictionary(dictionary, EurostagNamingStrategy.NameType.VSC, converterStationsIds);

        for (DanglingLine dl : Identifiables.sort(network.getDanglingLines())) {
            // skip if not in the main connected component
            if (config.isExportMainCCOnly() && !EchUtil.isInMainCc(dl, config.isNoSwitch())) {
                LOGGER.trace("dangling line not mapped, not in main component: {}", dl.getId());
                continue;
            }
            ConnectionBus bus1 = ConnectionBus.fromTerminal(dl.getTerminal(), config, fakeNodes);
            ConnectionBus bus2 = new ConnectionBus(true, EchUtil.getBusId(dl));
            dictionary.addIfNotExist(dl.getId(), new EsgBranchName(new Esg8charName(dictionary.getEsgId(bus1.getId())),
                    new Esg8charName(dictionary.getEsgId(bus2.getId())),
                    '1').toString());
        }

        for (VoltageLevel vl : Identifiables.sort(network.getVoltageLevels())) {
            for (Switch sw : Identifiables.sort(EchUtil.getSwitches(vl, config))) {
                Bus bus1 = EchUtil.getBus1(vl, sw.getId(), config);
                Bus bus2 = EchUtil.getBus2(vl, sw.getId(), config);
                // skip switches not in the main connected component
                if (config.isExportMainCCOnly() && (!EchUtil.isInMainCc(bus1) || !EchUtil.isInMainCc(bus2))) {
                    LOGGER.trace("switch not mapped, not in main component: {}", sw.getId());
                    continue;
                }
                dictionary.addIfNotExist(sw.getId(),
                        new EsgBranchName(new Esg8charName(dictionary.getEsgId(bus1.getId())),
                                new Esg8charName(dictionary.getEsgId(bus2.getId())),
                                parallelIndexes.getParallelIndex(sw.getId())).toString());
            }
        }

        for (Line l : Identifiables.sort(network.getLines())) {
            // skip lines not in the main connected component
            if (config.isExportMainCCOnly() && !EchUtil.isInMainCc(l, config.isNoSwitch())) {
                LOGGER.trace("line not mapped, not in main component: {}", l.getId());
                continue;
            }
            ConnectionBus bus1 = ConnectionBus.fromTerminal(l.getTerminal1(), config, fakeNodes);
            ConnectionBus bus2 = ConnectionBus.fromTerminal(l.getTerminal2(), config, fakeNodes);
            EsgBranchName ebname = new EsgBranchName(new Esg8charName(dictionary.getEsgId(bus1.getId())),
                    new Esg8charName(dictionary.getEsgId(bus2.getId())),
                    parallelIndexes.getParallelIndex(l.getId()));
            dictionary.addIfNotExist(l.getId(), ebname.toString());
        }

        for (TwoWindingsTransformer twt : Identifiables.sort(network.getTwoWindingsTransformers())) {
            // skip transformers not in the main connected component
            if (config.isExportMainCCOnly() && !EchUtil.isInMainCc(twt, config.isNoSwitch())) {
                LOGGER.trace("two windings transformer not mapped, not in main component: {}", twt.getId());
                continue;
            }
            ConnectionBus bus1 = ConnectionBus.fromTerminal(twt.getTerminal1(), config, fakeNodes);
            ConnectionBus bus2 = ConnectionBus.fromTerminal(twt.getTerminal2(), config, fakeNodes);
            dictionary.addIfNotExist(twt.getId(), new EsgBranchName(new Esg8charName(dictionary.getEsgId(bus1.getId())),
                    new Esg8charName(dictionary.getEsgId(bus2.getId())),
                    parallelIndexes.getParallelIndex(twt.getId())).toString());
        }

        for (ThreeWindingsTransformer t3wt : Identifiables.sort(network.getThreeWindingsTransformers())) {
            // skip transformers not in the main connected component
            if (config.isExportMainCCOnly() && !EchUtil.isInMainCc(t3wt, config.isNoSwitch())) {
                LOGGER.trace("three windings transformer not mapped, not in main component: {}", t3wt.getId());
                continue;
            }
            ConnectionBus bus1 = ConnectionBus.fromTerminal(t3wt.getLeg1().getTerminal(), config, fakeNodes);
            ConnectionBus bus2 = ConnectionBus.fromTerminal(t3wt.getLeg2().getTerminal(), config, fakeNodes);
            ConnectionBus bus3 = ConnectionBus.fromTerminal(t3wt.getLeg3().getTerminal(), config, fakeNodes);
            dictionary.addIfNotExist(t3wt.getId(), new EsgThreeWindingTransformer.EsgT3WName(new Esg8charName("T3W"),
                    new Esg8charName(dictionary.getEsgId(bus1.getId())),
                    new Esg8charName(dictionary.getEsgId(bus2.getId())),
                    new Esg8charName(dictionary.getEsgId(bus3.getId()))).toString());
        }

        return dictionary;
    }

    private EurostagDictionary(EurostagEchExportConfig config) {
        this(new HashMap<>(), config);
    }

    private EurostagDictionary(Map<String, String> iidmId2esgId, EurostagEchExportConfig config) {
        Objects.requireNonNull(iidmId2esgId);
        Objects.requireNonNull(config);
        this.iidmId2esgId = HashBiMap.create(iidmId2esgId);
        this.config = config;
    }

    public void add(String iidmId, String esgId) {
        if (iidmId2esgId.containsKey(iidmId)) {
            String errorMsg = "IIDM id '" + iidmId + "' already exists in the dictionary";
            LOGGER.error(errorMsg);
            throw new EsgException(errorMsg);
        }
        iidmId2esgId.put(iidmId, esgId);
    }

    public void addIfNotExist(String iidmId, String esgId) {
        if (!iidmId2esgId.containsKey(iidmId)) {
            if (iidmId2esgId.inverse().containsKey(esgId)) {
                String errorMsg = "Esg id '" + esgId + "' is already associated to IIDM id '"
                        + iidmId2esgId.inverse().get(esgId) + "' impossible to associate it to IIDM id '" + iidmId + "'";
                LOGGER.error(errorMsg);
                throw new EsgException(errorMsg);
            }
            iidmId2esgId.put(iidmId, esgId);
        }
    }

    public String getEsgId(String iidmId) {
        if (!iidmId2esgId.containsKey(iidmId)) {
            String errorMsg = "IIDM id '" + iidmId + "' + not found in the dictionary";
            LOGGER.error(errorMsg);
            throw new EsgException(errorMsg);
        }
        return iidmId2esgId.get(iidmId);
    }

    public String getIidmId(String esgId) {
        if (!iidmId2esgId.containsValue(esgId)) {
            String errorMsg = "ESG id '" + esgId + "' + not found in the dictionary";
            LOGGER.error(errorMsg);
            throw new EsgException(errorMsg);
        }
        return iidmId2esgId.inverse().get(esgId);
    }

    public boolean iidmIdExists(String iidmId) {
        return iidmId2esgId.containsKey(iidmId);
    }

    public boolean esgIdExists(String esgId) {
        return iidmId2esgId.inverse().containsKey(esgId);
    }

    public Map<String, String> toMap() {
        return iidmId2esgId;
    }

    public void load(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                String[] tokens = line.split(";");
                add(tokens[0], tokens[1]);
            }
        } catch (IOException e) {
            throw new EsgException(e);
        }
    }

    public void dump(Path file) {
        try (BufferedWriter os = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : iidmId2esgId.entrySet()) {
                os.write(entry.getKey() + ";" + entry.getValue() + ";");
                os.newLine();
            }
        } catch (IOException e) {
            throw new EsgException(e);
        }
    }

    public EurostagEchExportConfig getConfig() {
        return config;
    }

}
