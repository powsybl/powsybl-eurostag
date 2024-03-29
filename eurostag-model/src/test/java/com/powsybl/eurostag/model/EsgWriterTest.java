/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.eurostag.model;

import com.powsybl.commons.AbstractConverterTest;
import com.powsybl.eurostag.model.io.EsgWriter;
import org.joda.time.LocalDate;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author Mathieu BAGUE {@literal <mathieu.bague at rte-france.com>}
 */
public class EsgWriterTest extends AbstractConverterTest {

    private void test(EsgNetwork network, String name, String date, String resource) throws IOException {
        Path actualPath = fileSystem.getPath(resource);

        EsgGeneralParameters parameters = new EsgGeneralParameters();
        parameters.setEditDate(LocalDate.parse(date));

        EsgWriter esgWriter = new EsgWriter(network, parameters, new EsgSpecialParameters());
        try (Writer writer = Files.newBufferedWriter(actualPath, StandardCharsets.UTF_8)) {
            esgWriter.write(writer, name);
        }

        try (InputStream actual = Files.newInputStream(actualPath)) {
            compareTxt(getClass().getResourceAsStream("/" + resource), actual);
        }
    }

    @Test
    public void test() throws IOException {
        EsgNetwork network = EsgFactory.create();
        test(network, "sim1/InitialState", "2016-03-01", "eurostag-tutorial-example1.ech");
    }

    @Test
    public void testHvdc() throws IOException {
        EsgNetwork network = EsgFactory.createHvdc();
        test(network, "hvdctest/InitialState", "2016-01-01", "eurostag-hvdc-test.ech");
    }

}
