/**
 * Copyright (c) 2016, All partners of the iTesla project (http://www.itesla-project.eu/consortium)
 * Copyright (c) 2017, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.powsybl.eurostag.converter;

import com.powsybl.commons.config.ModuleConfig;
import com.powsybl.commons.config.PlatformConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Optional;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.it>
 */
public class DicoEurostagNamingStrategyFactory implements EurostagNamingStrategyFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(DicoEurostagNamingStrategyFactory.class);
    private static final String CONFIG_SECTION_NAME = "eurostag-naming-strategy-dico";
    private static final String CONFIG_PROPERTY_DICO_FILE_NAME = "dicoFile";

    @Override
    public EurostagNamingStrategy create() {
        Optional<ModuleConfig> config = PlatformConfig.defaultConfig().getOptionalModuleConfig(CONFIG_SECTION_NAME);
        if (config.isPresent()) {
            ModuleConfig moduleConfig = config.get();
            Path dicoFile = moduleConfig.getPathProperty(CONFIG_PROPERTY_DICO_FILE_NAME, null);
            LOGGER.info("Instantiating DicoEurostagNamingStrategy: property {}={} declared in config section '{}'", CONFIG_PROPERTY_DICO_FILE_NAME, dicoFile, CONFIG_SECTION_NAME);
            dicoFile = moduleConfig.getPathProperty(CONFIG_PROPERTY_DICO_FILE_NAME);
            return new DicoEurostagNamingStrategy(dicoFile);
        } else {
            LOGGER.warn("Cannot instantiate DicoEurostagNamingStrategy: config section '{}' not found  . Using CutEurostagNamingStrategy, instead.", CONFIG_SECTION_NAME);
            return new CutEurostagNamingStrategy();
        }
    }
}
