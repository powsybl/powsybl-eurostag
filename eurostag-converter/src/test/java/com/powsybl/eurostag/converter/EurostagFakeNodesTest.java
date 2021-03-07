/**
 * Copyright (c) 2017, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.eurostag.converter;

import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import org.junit.Test;

import static org.junit.Assert.assertTrue;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.it>
 */
public class EurostagFakeNodesTest {

    @Test
    public void test() {
        Network network = EurostagTutorialExample1Factory.create();
        EurostagFakeNodes fakeNodes = EurostagFakeNodes.build(network, new EurostagEchExportConfig());
        assertTrue(fakeNodes.esgIdsAsStream().count() >= network.getVoltageLevelCount());
    }

}
