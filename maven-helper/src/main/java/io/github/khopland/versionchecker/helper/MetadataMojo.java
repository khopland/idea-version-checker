package io.github.khopland.versionchecker.helper;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.metadata.DefaultMetadata;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.MetadataRequest;
import org.eclipse.aether.resolution.MetadataResult;
import org.eclipse.aether.transfer.MetadataNotFoundException;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

/** No dependency trees or JARs are resolved. Maven supplies settings, transports and credentials. */
public final class MetadataMojo extends AbstractMojo {
    private static final DateTimeFormatter LAST_UPDATED = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private MavenProject project;
    private MavenSession session;
    private RepositorySystem repositorySystem;
    private ProjectBuilder projectBuilder;
    private File input;
    private File output;
    private int threads;

    @Override public void execute() throws MojoExecutionException {
        try {
            Properties request = new Properties();
            try (InputStream stream = Files.newInputStream(input.toPath())) { request.load(stream); }
            // Maven 4 selects an aggregator's children too. Only the requested POM owns this response.
            String requestedProject = request.getProperty("project");
            if (requestedProject != null && !Files.isSameFile(project.getFile().toPath(), java.nio.file.Path.of(requestedProject))) return;
            Properties response = new Properties();
            response.setProperty("protocol", "2");
            int count = Integer.parseInt(request.getProperty("count"));
            if (count < 0 || count > 512) throw new IllegalArgumentException("Invalid metadata batch size");
            response.setProperty("count", Integer.toString(count));
            DefaultRepositorySystemSession resolver = new DefaultRepositorySystemSession(session.getRepositorySession());
            // A cache miss represents fresh work. Never edit shared resolver-status files here.
            resolver.setUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_ALWAYS);
            resolver.setConfigProperty("aether.metadataResolver.threads", Math.max(1, Math.min(4, threads)));
            List<MetadataRequest> metadataRequests = new ArrayList<>();
            List<Integer> owners = new ArrayList<>();
            var repositoriesByKind = new HashMap<Boolean, List<RemoteRepository>>();
            if (count == 0) {
                for (boolean plugin : new boolean[] {false, true}) {
                    List<String> ids = repositories(resolver, repositoriesByKind, plugin).stream()
                        .filter(repository -> repository.getPolicy(false).isEnabled()).map(RemoteRepository::getId).toList();
                    response.setProperty(plugin ? "pluginRepositories" : "repositories", String.join("\n", ids));
                }
            }
            List<ArtifactRequest> releaseRequests = new ArrayList<>();
            List<Integer> releaseOwners = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                String prefix = i + ".";
                String group = request.getProperty(prefix + "group");
                String artifact = request.getProperty(prefix + "artifact");
                String version = request.getProperty(prefix + "version", "");
                boolean plugin = Boolean.parseBoolean(request.getProperty(prefix + "plugin"));
                if (Boolean.parseBoolean(request.getProperty(prefix + "release"))) {
                    // Only the POM: Maven keeps the server's Last-Modified as its file time.
                    releaseRequests.add(new ArtifactRequest(new DefaultArtifact(group, artifact, "", "pom", version),
                        repositories(resolver, repositoriesByKind, plugin), "version-checker"));
                    releaseOwners.add(i);
                } else if (!version.isEmpty()) {
                    try {
                        var build = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
                        build.setRepositorySession(resolver);
                        build.setRemoteRepositories(plugin ? project.getPluginArtifactRepositories() : project.getRemoteArtifactRepositories());
                        build.setResolveDependencies(false);
                        build.setProcessPlugins(false);
                        var pomArtifact = new org.apache.maven.artifact.DefaultArtifact(group, artifact, version,
                            "compile", "pom", null, new org.apache.maven.artifact.handler.DefaultArtifactHandler("pom"));
                        MavenProject pom = projectBuilder.build(pomArtifact, build).getProject();
                        String required = pom.getPrerequisites() == null ? "" : pom.getPrerequisites().getMaven();
                        response.setProperty(prefix + "requiredMaven", required == null ? "" : required);
                    } catch (Exception failure) {
                        response.setProperty(prefix + "error", failure.getClass().getSimpleName() + ": Could not read plugin prerequisites");
                    }
                } else {
                    // Mirror/policy application depends on repository kind, not the artifact.
                    List<RemoteRepository> repositories = repositories(resolver, repositoriesByKind, plugin);
                    Metadata metadata = new DefaultMetadata(group, artifact, "maven-metadata.xml", Metadata.Nature.RELEASE);
                    for (RemoteRepository repository : repositories) {
                        if (!repository.getPolicy(false).isEnabled()) continue;
                        metadataRequests.add(new MetadataRequest(metadata, repository, "version-checker").setFavorLocalRepository(false));
                        owners.add(i);
                    }
                    response.setProperty(prefix + "versions", "");
                }
            }
            List<MetadataResult> results = repositorySystem.resolveMetadata(resolver, metadataRequests);
            List<LinkedHashSet<String>> versions = new ArrayList<>();
            for (int i = 0; i < count; i++) versions.add(new LinkedHashSet<>());
            // Newest repository update per artifact; -1 marks a repository without a usable timestamp.
            long[] lastUpdated = new long[count];
            DocumentBuilder parser = null;
            for (int j = 0; j < results.size(); j++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                int owner = owners.get(j);
                String prefix = owner + ".";
                MetadataResult result = results.get(j);
                if (result.getException() != null && !(result.getException() instanceof MetadataNotFoundException)) {
                    // Do not report an authentication/transport failure as an empty successful check.
                    response.setProperty(prefix + "error", result.getException().getClass().getSimpleName() +
                        ": Repository metadata lookup failed for " + metadataRequests.get(j).getRepository().getId());
                    continue;
                }
                File file = result.getMetadata() == null ? null : result.getMetadata().getFile();
                if (file == null || !file.isFile()) continue;
                try {
                    // Results are parsed serially. Keep one secured parser per goal, including
                    // after a malformed sibling; no parser is shared between Maven workers.
                    if (parser == null) {
                        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                        parser = factory.newDocumentBuilder();
                    }
                    var document = parser.parse(file);
                    NodeList containers = document.getElementsByTagName("versions");
                    NodeList entries = containers.getLength() == 0 ? null : containers.item(0).getChildNodes();
                    boolean listed = false;
                    for (int k = 0; entries != null && k < entries.getLength(); k++) {
                        if (!"version".equals(entries.item(k).getNodeName())) continue;
                        String value = entries.item(k).getTextContent().trim();
                        if (!value.isEmpty()) { versions.get(owner).add(value); listed = true; }
                    }
                    if (listed && lastUpdated[owner] >= 0) {
                        NodeList updated = document.getElementsByTagName("lastUpdated");
                        long time = updated.getLength() == 0 ? -1 : lastUpdated(updated.item(0).getTextContent());
                        lastUpdated[owner] = time < 0 ? -1 : Math.max(lastUpdated[owner], time);
                    }
                } catch (Exception failure) {
                    response.setProperty(prefix + "error", "Malformed repository metadata");
                }
            }
            for (int i = 0; i < count; i++) if (response.containsKey(i + ".versions")) {
                response.setProperty(i + ".versions", String.join("\n", versions.get(i)));
                if (lastUpdated[i] > 0) response.setProperty(i + ".lastUpdated", Long.toString(lastUpdated[i]));
            }
            if (!releaseRequests.isEmpty()) releases(resolver, releaseRequests, releaseOwners, response);
            try (OutputStream stream = Files.newOutputStream(output.toPath())) { response.store(stream, null); }
        } catch (Exception failure) {
            throw new MojoExecutionException("Version metadata batch failed", failure);
        }
    }

    private List<RemoteRepository> repositories(DefaultRepositorySystemSession resolver,
                                                HashMap<Boolean, List<RemoteRepository>> cache, boolean plugin) {
        // Mirror/policy application depends on repository kind, not the artifact.
        return cache.computeIfAbsent(plugin, kind -> repositorySystem.newResolutionRepositories(resolver,
            kind ? project.getRemotePluginRepositories() : project.getRemoteProjectRepositories()));
    }

    /** Release POMs are immutable, so a POM already in the local repository needs no request. */
    private void releases(DefaultRepositorySystemSession resolver, List<ArtifactRequest> requests, List<Integer> owners,
                          Properties response) {
        List<ArtifactResult> results;
        try { results = repositorySystem.resolveArtifacts(resolver, requests); }
        catch (ArtifactResolutionException failure) { results = failure.getResults(); }
        for (int j = 0; j < requests.size(); j++) {
            String prefix = owners.get(j) + ".";
            ArtifactResult result = j < results.size() ? results.get(j) : null;
            File file = result == null || result.getArtifact() == null ? null : result.getArtifact().getFile();
            long published = file == null ? 0 : file.lastModified();
            if (published > 0) response.setProperty(prefix + "published", Long.toString(published));
            else response.setProperty(prefix + "error", (result == null || result.getExceptions().isEmpty() ? "IOException" :
                result.getExceptions().get(0).getClass().getSimpleName()) + ": Could not read the release date");
        }
    }

    static long lastUpdated(String value) {
        try {
            return LocalDateTime.parse(value.trim(), LAST_UPDATED).toInstant(ZoneOffset.UTC).toEpochMilli();
        } catch (RuntimeException invalid) {
            return -1;
        }
    }
}
