/**
 * 
 */
package com.teaglu.dnsalias;

import java.util.concurrent.CountDownLatch;

import org.eclipse.jdt.annotation.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.teaglu.composite.Composite;
import com.teaglu.configure.config.ConfigManager;
import com.teaglu.configure.config.ConfigManagerFactory;
import com.teaglu.configure.config.ConfigTarget;
import com.teaglu.configure.secret.SecretProvider;
import com.teaglu.configure.secret.SecretProviderFactory;
import com.teaglu.configure.secret.replacer.AtIdSecretReplacer;
import com.teaglu.dnsalias.scheduler.Scheduler;
import com.teaglu.dnsalias.scheduler.impl.ExecutorScheduler;

/**
 * Main
 * 
 * Contains the main entry point of the program.
 * 
 * At some point this may also contain the correct entrypoint to use this as an AWS lambda.
 *
 */
public class Main {
	private static final String VERSION= "0.0.5";
	
    private static final Logger log= LoggerFactory.getLogger(Main.class);
    private static final CountDownLatch quitLatch= new CountDownLatch(1);
    
    public static void main(String args[]) {
		log.info("DNS Alias Version " + VERSION + " Starting");
    	
    	Scheduler scheduler= ExecutorScheduler.Create();
    	scheduler.start();
    	
        try {
            // Create a secret provider based on environment
            SecretProvider secretProvider= SecretProviderFactory
                    .getInstance()
                    .createFromEnvironment();

            // Create a target for the configuration manager to operate on
            ConfigTarget target= new ConfigTarget() {
                @Override
                public void apply(@NonNull Composite config) throws Exception {
                    scheduler.configure(config, secretProvider);
                }

                @Override
                public void shutdown() {
                    quitLatch.countDown();  
                }
            };

            // Create a configuration manager based on environment
            ConfigManager configManager= ConfigManagerFactory
                    .getInstance()
                    .createFromEnvironment(target, AtIdSecretReplacer.Create(secretProvider));
            
            // Start the configuration manager
            configManager.start();

            // The main thread just waits on a latch until it's time to shut everything down.
            for (boolean run= true; run;) {
                try {
                    quitLatch.await();
                    run= false;
                } catch (InterruptedException e) {
                }
            }

            // Shut down the manager normally
            configManager.stop();

        } catch (Exception e) {
            log.error("Error in main startup", e);
        }

        if (scheduler != null) {
        	scheduler.stop();
        }
    }
}
