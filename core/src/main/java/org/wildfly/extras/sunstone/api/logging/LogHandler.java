package org.wildfly.extras.sunstone.api.logging;

import org.wildfly.extras.sunstone.api.Node;

/**
 * @author Jan Martiska
 */
public interface LogHandler {

    void handleLog(String log, Node node);

    void handleError(String error, Node node);

}
