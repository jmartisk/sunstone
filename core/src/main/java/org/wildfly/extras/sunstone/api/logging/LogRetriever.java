package org.wildfly.extras.sunstone.api.logging;


import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wildfly.extras.sunstone.api.Node;
import org.wildfly.extras.sunstone.api.ssh.CommandExecution;
import org.wildfly.extras.sunstone.api.ssh.SshClient;

/**
 * @author Jan Martiska
 */
public class LogRetriever {

    private final String remotePath;
    private final Node node;
    private static final Logger logger = LoggerFactory.getLogger(LogRetriever.class);
    private ConsumerThread thread;

    private List<LogHandler> handlers;

    public LogRetriever(String remotePath, Node node, LogHandler... handlers) {
        this.remotePath = remotePath;
        this.node = node;
        this.handlers = new CopyOnWriteArrayList<>();
        Collections.addAll(this.handlers, handlers);
    }

    public void start() {
        if (!node.isRunning()) {
            throw new IllegalStateException(node.getName() + " is not running");
        }
        logger.info("Gobbling file " + remotePath + " from node " + node.getName());
        try {
            final SshClient ssh = node.ssh();  // TODO this needs to be closed at some point in time
            final CommandExecution exec = ssh.exec("sudo tail -f " + remotePath);
            thread = new ConsumerThread(exec.stdout(), exec.stderr());
            thread.start();
        } catch(Exception e) {
            // TODO fix this
            throw new RuntimeException(e);
        }
    }

    public void finish() {
        thread.interrupt(); // FIXME how to better tell the thread to stop reading
    }

    private class ConsumerThread extends Thread {

        private final InputStream input;
        private final InputStream errInput;

        ConsumerThread(InputStream input, InputStream errInput) {
            super();
            this.input = input;
            this.errInput = errInput;
        }

        @Override
        public void run() {
            try (Reader ir = new InputStreamReader(input)) {
                try (BufferedReader bir = new BufferedReader(ir)) {
                    try (Reader er = new InputStreamReader(errInput)) {
                        try (BufferedReader ber = new BufferedReader(er)) {
                            while(!Thread.interrupted()) { // TODO this needs a more appropriate decision when to stop reading
                                if(bir.ready()) {
                                    final String line = bir.readLine();
                                    LogRetriever.this.handlers.forEach(handler -> handler.handleLog(line, LogRetriever.this.node));
                                }
                                if(ber.ready()) {
                                    final String line = ber.readLine();
                                    LogRetriever.this.handlers.forEach(handler -> handler.handleError(line, LogRetriever.this.node));
                                }
                            }
                        }
                    }
                }
            } catch(Exception e) {
                // TODO fix
                throw new RuntimeException(e);
            }

        }

    }

}
