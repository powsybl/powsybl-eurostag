/**
 * Copyright (c) 2017, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.powsybl.eurostag.converter;

import com.powsybl.iidm.network.Network;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.it>
 */
public interface EurostagEchExporterFactory {

    EurostagEchExporter createEchExporter(Network network, EurostagEchExportConfig exportConfig, BranchParallelIndexes parallelIndexes, EurostagDictionary dictionary, EurostagFakeNodes fakeNodes);

}
