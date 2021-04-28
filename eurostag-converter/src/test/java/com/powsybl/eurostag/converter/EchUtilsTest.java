/**
 * Copyright (c) 2018, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.eurostag.converter;

import com.google.common.collect.Lists;
import com.powsybl.iidm.network.HvdcConverterStation;
import com.powsybl.iidm.network.HvdcLine;
import com.powsybl.iidm.network.Network;
import com.powsybl.iidm.network.test.EurostagTutorialExample1Factory;
import com.powsybl.iidm.network.test.HvdcTestNetwork;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * @author Christian Biasuzzi <christian.biasuzzi@techrain.it>
 */
public class EchUtilsTest {

    private Network network;
    private Network networkHvdc;

    @Before
    public void setUp() {
        network = EurostagTutorialExample1Factory.create();
        networkHvdc = HvdcTestNetwork.createVsc();
    }

    private EurostagEchExportConfig getConfig(boolean noSwitch, boolean exportMainCCOnly) {
        return new EurostagEchExportConfig(exportMainCCOnly, noSwitch);
    }

    @Test
    public void testGetHvdcLineDcVoltage() {
        networkHvdc.getHvdcLineStream().forEach(line -> assertEquals(line.getNominalV() * 2.0, EchUtil.getHvdcLineDcVoltage(line), 0.0));
    }

    @Test
    public void testGetHvdcLineDcVoltageNull() {
        Assert.assertThrows("", NullPointerException.class, () -> EchUtil.getHvdcLineDcVoltage(null));
    }

    @Test
    public void testIsImMainCcBus() {
        Lists.newArrayList(true, false).forEach(exportMainCCOnly ->
            EchUtil.getBuses(networkHvdc, getConfig(false, exportMainCCOnly)).forEach(bus ->
                    assertTrue(EchUtil.isInMainCc(bus))
            )
        );
    }

    @Test
    public void testIsImMainCcGen() {
        Lists.newArrayList(true, false).forEach(exportMainCCOnly ->
            network.getGeneratorStream().forEach(gen -> assertTrue(EchUtil.isInMainCc(gen, exportMainCCOnly)))
        );
    }

    @Test
    public void testIsImMainCcLine() {
        Lists.newArrayList(true, false).forEach(exportMainCCOnly ->
            network.getLines().forEach(line -> assertTrue(EchUtil.isInMainCc(line, exportMainCCOnly)))
        );
    }

    @Test
    public void testIsPmode() {
        HvdcLine hline = networkHvdc.getHvdcLine("L");
        assertTrue(EchUtil.isPMode(networkHvdc.getVscConverterStation("C2"), hline));
        assertFalse(EchUtil.isPMode(networkHvdc.getVscConverterStation("C1"), hline));
    }

    @Test
    public void testGetPVstation() {
        HvdcLine hline = networkHvdc.getHvdcLine("L");
        HvdcConverterStation<?> pStation = EchUtil.getPStation(hline);
        assertNotNull(pStation);
        assertEquals(networkHvdc.getVscConverterStation("C2").getId(), pStation.getId());

        HvdcConverterStation<?> vStation = EchUtil.getVStation(hline);
        assertNotNull(vStation);
        assertEquals(networkHvdc.getVscConverterStation("C1").getId(), vStation.getId());
    }
}
