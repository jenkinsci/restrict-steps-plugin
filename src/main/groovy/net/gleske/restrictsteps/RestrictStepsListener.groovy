package net.gleske.restrictsteps

import com.cloudbees.groovy.cps.Outcome
import hudson.Extension
import hudson.ExtensionList
import hudson.model.Result
import hudson.model.Run
import hudson.model.TaskListener
import jenkins.model.CauseOfInterruption
import jenkins.model.Jenkins
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition
import org.jenkinsci.plugins.workflow.flow.StepListener
import org.jenkinsci.plugins.workflow.libs.LibraryAdder
import org.jenkinsci.plugins.workflow.steps.FlowInterruptedException
import org.jenkinsci.plugins.workflow.steps.Step
import org.jenkinsci.plugins.workflow.steps.StepContext

import edu.umd.cs.findbugs.annotations.NonNull
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger
import org.jenkinsci.plugins.configfiles.GlobalConfigFiles
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

@Extension
class RestrictStepsListener implements StepListener {
    private class BlockedStepCause extends CauseOfInterruption {
        private String message
        BlockedStepCause(String message) {
            this.message = message
        }
        @Override public String getShortDescription() {
            return this.message;
        }
    }
    // ID within config file provider plugin to get YAML content
    private static String config_id = 'restricted-steps'
    private static final Logger logger = Logger.getLogger(RestrictStepsListener.class.getName());
    private transient ConcurrentHashMap restricted
    private transient String config

    private Map parseYaml(def content) {
        LoaderOptions options = new LoaderOptions()
        options.allowDuplicateKeys = true
        options.allowRecursiveKeys = false
        // 5MB data limit?  code point limit is not well explained
        options.codePointLimit = 5242880
        options.maxAliasesForCollections = 500
        options.nestingDepthLimit = 500
        options.processComments = false
        options.wrappedToRootException = false
        def yaml = new Yaml(new SafeConstructor(options))
        yaml.load(content)
    }

    Map getRestricted() {
        def config_files = Jenkins.instance.getExtensionList(GlobalConfigFiles)[0]
        if(config_files.getById(config_id)) {
            if(config != config_files.getById(config_id).content) {
                try {
                    parseYaml(config).each { k, v ->
                        restricted[k] = v
                    }
                    config = config_files.getById(config_id).content
                } catch(Exception ignored) {}
            }
        }

        Collections.unmodifiableMap(restricted ?: [:])
    }

    /**
      Search pipeline steps

      https://github.com/jenkinsci/workflow-cps-plugin/blob/a9795f98880c8fab08a904c1620bdf27f96faa8b/plugin/src/main/java/org/jenkinsci/plugins/workflow/cps/DSL.java#L312-L326
      */
    @Override
    void notifyOfNewStep(@NonNull Step step, @NonNull StepContext context) {
        String stepName = step.getDescriptor().getFunctionName()
        final Run run = context.get(Run)
        final CpsFlowDefinition flowDefinition = context.get(CpsFlowDefinition)
        TaskListener listener = context.get(TaskListener)
        logger.fine("""\
            Running step: ${step.class}
            step class: ${stepName}
            sandbox: ${flowDefinition?.isSandbox() ?: false}
            """.stripIndent().trim())
        if(!(getRestricted()?.steps in List)) {
            return
        }
        // notify context to abort job
        if(stepName in restricted?.steps) {
            FlowInterruptedException cause = new FlowInterruptedException(Result.FAILURE, new BlockedStepCause("ERROR: ${stepName} step not allowed via restricted steps by admin."));
            context.completed(new Outcome(null, cause))
        }
    }

}
