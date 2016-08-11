package org.wildfly.extras.sunstone.api.logging.handlers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.sunstone.api.Node;
import org.wildfly.extras.sunstone.api.logging.LogHandler;

/**
 * @author Jan Martiska
 */
public class ConsoleEcho implements LogHandler {

    private static final Logger logger = LoggerFactory.getLogger(ConsoleEcho.class);

    public void handleLog(String log, Node node) {
        logger.info("[## " + node.getName() +" ##] " + log);
    }

    public void handleError(String error, Node node) {
        logger.info("[ERROR ## " + node.getName() +" ##] " + error);
    }
}
