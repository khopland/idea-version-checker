package io.github.khopland.versionchecker.helper;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.metadata.DefaultMetadata;
import org.eclipse.aether.metadata.Metadata;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.MetadataRequest;
import org.eclipse.aether.resolution.MetadataResult;
import org.eclipse.aether.transfer.MetadataNotFoundException;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;

/** No dependency trees or JARs are resolved. Maven supplies settings, transports and credentials. */
public final class MetadataMojo extends AbstractMojo {
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
            Properties response = new Properties();
            response.setProperty("protocol", "1");
            int count = Integer.parseInt(request.getProperty("count"));
            if (count < 0 || count > 512) throw new IllegalArgumentException("Invalid metadata batch size");
            response.setProperty("count", Integer.toString(count));
            DefaultRepositorySystemSession resolver = new DefaultRepositorySystemSession(session.getRepositorySession());
            // A cache miss represents fresh work. Never edit shared resolver-status files here.
            resolver.setUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_ALWAYS);
            resolver.setConfigProperty("aether.metadataResolver.threads", Math.max(1, Math.min(4, threads)));
            List<MetadataRequest> metadataRequests = new ArrayList<>();
            List<Integer> owners = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                String prefix = i + ".";
                String group = request.getProperty(prefix + "group");
                String artifact = request.getProperty(prefix + "artifact");
                String version = request.getProperty(prefix + "version", "");
                boolean plugin = Boolean.parseBoolean(request.getProperty(prefix + "plugin"));
                List<RemoteRepository> repositories = repositorySystem.newResolutionRepositories(resolver,
                    plugin ? project.getRemotePluginRepositories() : project.getRemoteProjectRepositories());
                if (!version.isEmpty()) {
                    try {
                        var build = new DefaultProjectBuildingRequest(session.getProjectBuildingRequest());
                        build.setRepositorySession(resolver);
                        build.setRemoteRepositories(plugin ? project.getPluginArtifactRepositories() : project.getRemoteArtifactRepositories());
                        build.setResolveDependencies(false);
                        build.setProcessPlugins(false);
                        var pomArtifact = session.getContainer().lookup(org.apache.maven.artifact.factory.ArtifactFactory.class)
                            .createProjectArtifact(group, artifact, version);
                        MavenProject pom = projectBuilder.build(pomArtifact, build).getProject();
                        String required = pom.getPrerequisites() == null ? "" : pom.getPrerequisites().getMaven();
                        response.setProperty(prefix + "requiredMaven", required == null ? "" : required);
                    } catch (Exception failure) {
                        response.setProperty(prefix + "error", failure.getClass().getSimpleName() + ": Could not read plugin prerequisites");
                    }
                } else {
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
                    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                    var document = factory.newDocumentBuilder().parse(file);
                    NodeList containers = document.getElementsByTagName("versions");
                    NodeList entries = containers.getLength() == 0 ? null : containers.item(0).getChildNodes();
                    for (int k = 0; entries != null && k < entries.getLength(); k++) {
                        if (!"version".equals(entries.item(k).getNodeName())) continue;
                        String value = entries.item(k).getTextContent().trim();
                        if (!value.isEmpty()) versions.get(owner).add(value);
                    }
                } catch (Exception failure) {
                    response.setProperty(prefix + "error", "Malformed repository metadata");
                }
            }
            for (int i = 0; i < count; i++) if (response.containsKey(i + ".versions"))
                response.setProperty(i + ".versions", String.join("\n", versions.get(i)));
            try (OutputStream stream = Files.newOutputStream(output.toPath())) { response.store(stream, null); }
        } catch (Exception failure) {
            throw new MojoExecutionException("Version metadata batch failed", failure);
        }
    }
}
