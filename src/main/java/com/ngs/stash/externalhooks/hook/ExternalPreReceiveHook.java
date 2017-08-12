package com.ngs.stash.externalhooks.hook;

import com.atlassian.bitbucket.hook.*;
import com.atlassian.bitbucket.hook.repository.*;
import com.atlassian.bitbucket.repository.*;
import com.atlassian.bitbucket.scm.BaseCommand;
import com.atlassian.bitbucket.scm.Command;
import com.atlassian.bitbucket.scm.CommandOutputHandler;
import com.atlassian.bitbucket.scm.ScmService;
import com.atlassian.bitbucket.setting.*;
import com.atlassian.bitbucket.user.*;
import com.atlassian.bitbucket.auth.*;
import com.atlassian.bitbucket.permission.*;
import com.atlassian.bitbucket.server.*;
import com.atlassian.bitbucket.util.*;
import com.atlassian.utils.process.ProcessException;
import com.atlassian.utils.process.Watchdog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.Collection;
import org.apache.commons.io.FilenameUtils;
import java.io.*;
import java.util.Map;
import java.util.List;
import java.util.LinkedList;
import java.util.Set;
import java.nio.file.Files;


public class ExternalPreReceiveHook
    implements PreReceiveRepositoryHook, RepositorySettingsValidator
{
    private static final Logger log = LoggerFactory.getLogger(
        ExternalPreReceiveHook.class);

    private AuthenticationContext authCtx;
    private PermissionService permissions;
    private RepositoryService repoService;
    private ApplicationPropertiesService properties;
    private ScmService scmService;

    public ExternalPreReceiveHook(
        AuthenticationContext authenticationContext,
        PermissionService permissions,
        RepositoryService repoService,
        ApplicationPropertiesService properties,
        ScmService scmService
    ) {
        this.authCtx = authenticationContext;
        this.permissions = permissions;
        this.repoService = repoService;
        this.properties = properties;
        this.scmService = scmService;
    }

    private static Field baseCommandEnvironmentField;

    static {
        try {
            baseCommandEnvironmentField = BaseCommand.class.getDeclaredField("environment");
            baseCommandEnvironmentField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            baseCommandEnvironmentField = null;
        }
    }

    /**
     * Call external executable as git hook.
     */
    @Override
    public boolean onReceive(
        RepositoryHookContext context,
        Collection<RefChange> refChanges,
        HookResponse hookResponse
    ) {
        Repository repo = context.getRepository();
        Settings settings = context.getSettings();

        // compat with < 3.2.0
        String repoPath = this.properties.getRepositoryDir(repo).getAbsolutePath();
        List<String> exe = new LinkedList<String>();

        ProcessBuilder pb = createProcessBuilder(repo, repoPath, exe, settings);

        try {
            int Result = runExternalHooks(pb, refChanges, hookResponse);
            return Result == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            log.error("Error running {} in {}", exe, repoPath, e);
            return false;
        }
    }

    private static class NullCommandOutputHandler implements CommandOutputHandler {

        @Override
        public Object getOutput() { return null; }

        @Override
        public void process(InputStream inputStream) throws ProcessException { }

        @Override
        public void complete() throws ProcessException { }

        @Override
        public void setWatchdog(Watchdog watchdog) { }

    }

    @SuppressWarnings("unchecked")
    public Map<String, String> getScmEnvironment(final Repository repository) {

        final Command<?> command = scmService.createBuilder(repository)
                .command("rev-list").build(new NullCommandOutputHandler());

        try {

            if (command instanceof BaseCommand) {
                return (Map) baseCommandEnvironmentField.get(command);
            }

        } catch (IllegalAccessException e) { }

        return null;
    }

    public ProcessBuilder createProcessBuilder(
        Repository repo, String repoPath, List<String> exe, Settings settings
    ) {
        exe.add(this.getExecutable(
            settings.getString("exe"),
            settings.getBoolean("safe_path", false)).getPath());

        if (settings.getString("params") != null) {
            for (String arg : settings.getString("params").split("\r\n")) {
                exe.add(arg);
            }
        }

        ApplicationUser currentUser = authCtx.getCurrentUser();
        ProcessBuilder pb = new ProcessBuilder(exe);

        Map<String, String> env = pb.environment();

        final Map<String, String> scmEnvironment = getScmEnvironment(repo);
        if (scmEnvironment != null) {
            env.putAll(scmEnvironment);
        }

        env.put("STASH_USER_NAME", currentUser.getName());
        if (currentUser.getEmailAddress() != null) {
            env.put("STASH_USER_EMAIL", currentUser.getEmailAddress());
        } else {
            log.error("Can't get user email address. getEmailAddress() call returns null");
        }
        env.put("STASH_REPO_NAME", repo.getName());

        boolean isAdmin = permissions.hasRepositoryPermission(
            currentUser, repo, Permission.REPO_ADMIN);
        boolean isWrite = permissions.hasRepositoryPermission(currentUser, repo, Permission.REPO_WRITE);
        boolean isDirectAdmin = permissions.hasDirectRepositoryUserPermission(repo, Permission.REPO_ADMIN);
        boolean isDirectWrite = permissions.hasDirectRepositoryUserPermission(repo, Permission.REPO_WRITE);
        env.put("STASH_IS_ADMIN", String.valueOf(isAdmin));
        env.put("STASH_IS_WRITE", String.valueOf(isWrite));
        env.put("STASH_IS_DIRECT_ADMIN", String.valueOf(isDirectAdmin));
        env.put("STASH_IS_DIRECT_WRITE", String.valueOf(isDirectWrite));
        env.put("STASH_REPO_IS_FORK", String.valueOf(repo.isFork()));

        RepositoryCloneLinksRequest.Builder cloneLinksRequestBuilder =
            new RepositoryCloneLinksRequest.Builder();

        cloneLinksRequestBuilder.repository(repo);

        RepositoryCloneLinksRequest cloneLinksRequest =
            cloneLinksRequestBuilder.build();

        Set<NamedLink> cloneLinks = this.repoService.getCloneLinks(
            cloneLinksRequest
        );

        for (NamedLink link : cloneLinks) {
            env.put(
                "STASH_REPO_CLONE_" + link.getName().toUpperCase(),
                link.getHref()
            );
        }

        env.put(
            "STASH_BASE_URL",
            this.properties.getBaseUrl().toString()
        );

        env.put("STASH_PROJECT_NAME", repo.getProject().getName());
        env.put("STASH_PROJECT_KEY", repo.getProject().getKey());

        pb.directory(new File(repoPath));
        pb.redirectErrorStream(true);

        return pb;
    }

    public int runExternalHooks(
        ProcessBuilder pb,
        Collection<RefChange> refChanges,
        HookResponse hookResponse
    ) throws InterruptedException, IOException {
        Process process = pb.start();
        InputStreamReader input = new InputStreamReader(
                                                        process.getInputStream(), "UTF-8");
        OutputStream output = process.getOutputStream();

        for (RefChange refChange : refChanges) {
            output.write(
                         (
                          refChange.getFromHash() + " " +
                          refChange.getToHash() + " " +
                          refChange.getRef().getId() + "\n"
                          ).getBytes("UTF-8")
                         );
        }
        output.close();

        boolean trimmed = false;
        if (hookResponse != null) {
            int data;
            int count = 0;
            while ((data = input.read()) >= 0) {
                if (count >= 65000) {
                    if (!trimmed) {
                        hookResponse.err().
                            print("\n");
                        hookResponse.err().
                            print("Hook response exceeds 65K length limit.\n");
                        hookResponse.err().
                            print("Further output will be trimmed.\n");
                        trimmed = true;
                    }
                    continue;
                }

                String charToWrite = Character.toString((char)data);

                count += charToWrite.getBytes("utf-8").length;

                hookResponse.err().print(charToWrite);
            }

        }

        return process.waitFor();
    }

    @Override
    public void validate(
        Settings settings,
        SettingsValidationErrors errors, Repository repository
    ) {
        if (!settings.getBoolean("safe_path", false)) {
            if (!permissions.hasGlobalPermission(
                    authCtx.getCurrentUser(), Permission.SYS_ADMIN)) {
                errors.addFieldError("exe",
                    "You should be a Bitbucket System Administrator to edit this field " +
                    "without \"safe mode\" option.");
                return;
            }
        }

        if (settings.getString("exe", "").isEmpty()) {
            errors.addFieldError("exe",
                "Executable is blank, please specify something");
            return;
        }

        File executable = this.getExecutable(
            settings.getString("exe",""),
            settings.getBoolean("safe_path", false));

        boolean isExecutable = false;
        if (executable != null) {
            try {
                isExecutable = executable.canExecute() && executable.isFile();
            } catch (SecurityException e) {
                log.error("Security exception on {}", executable.getPath(), e);
                isExecutable = false;
            }
        } else {
            errors.addFieldError("exe",
                "Specified path for executable can not be resolved.");
            return;
        }

        if (!isExecutable) {
            errors.addFieldError("exe",
                "Specified path is not executable file. Check executable flag.");
            return;
        }

        log.info("Setting executable {}", executable.getPath());
    }

    public File getExecutable(String path, boolean safeDir) {
        File executable = new File(path);
        if (safeDir) {
            path = FilenameUtils.normalize(path);
            if (path == null) {
                executable = null;
            } else {
                String safeBaseDir =
                    this.properties.getHomeDir().getAbsolutePath() +
                    "/external-hooks/";
                executable = new File(safeBaseDir, path);
            }
        }

        return executable;
    }
}
