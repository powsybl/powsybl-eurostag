/**
 * Copyright (c) 2021, RTE (http://www.rte-france.com)
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.powsybl.eurostag.model;

/**
 * @author Mathieu BAGUE {@literal <mathieu.bague at rte-france.com>}
 */
public class EsgException extends RuntimeException {

    public EsgException(String message) {
        super(message);
    }

    public EsgException(Throwable cause) {
        super(cause);
    }

    public EsgException(String message, Throwable cause) {
        super(message, cause);
    }
}
