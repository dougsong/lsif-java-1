package com.microsoft.java.lsif.core.internal.indexer;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.ITypeRoot;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.ls.core.internal.JavaLanguageServerPlugin;
import org.eclipse.jdt.ls.core.internal.ResourceUtils;
import org.eclipse.lsp4j.ClientCapabilities;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.jsonrpc.json.adapters.CollectionTypeAdapterFactory;
import org.eclipse.lsp4j.jsonrpc.json.adapters.EnumTypeAdapterFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.microsoft.java.lsif.core.internal.IConstant;
import com.microsoft.java.lsif.core.internal.LanguageServerIndexerPlugin;
import com.microsoft.java.lsif.core.internal.emitter.Emitter;
import com.microsoft.java.lsif.core.internal.emitter.JsonEmitter;
import com.microsoft.java.lsif.core.internal.indexer.handlers.DocumentSymbolHandler;
import com.microsoft.java.lsif.core.internal.indexer.handlers.WorkspaceHandler;
import com.microsoft.java.lsif.core.internal.protocol.Document;
import com.microsoft.java.lsif.core.internal.protocol.DocumentSymbolResult;
import com.microsoft.java.lsif.core.internal.protocol.JavaLsif;
import com.microsoft.java.lsif.core.internal.protocol.Project;

public class Indexer {

	private static final Gson gson = new GsonBuilder().registerTypeAdapterFactory(new CollectionTypeAdapterFactory())
			.registerTypeAdapterFactory(new EnumTypeAdapterFactory()).create();

	private WorkspaceHandler handler;

	public Indexer() {
		this.handler = new WorkspaceHandler(System.getProperty("intellinav.repo.path"));
	}

	public void buildModel() {

		NullProgressMonitor monitor = new NullProgressMonitor();

		List<IPath> projectRoots = this.handler.initialize();
		initializeJdtls();

		JsonEmitter emitter = new JsonEmitter();

		for (IPath path : projectRoots) {
			try {
				LanguageServerIndexerPlugin.logInfo("Starting index project: " + path.toPortableString());
				handler.importProject(path, monitor);
				handler.buildProject(monitor);
				buildIndex(path, monitor, emitter);
				handler.removeProject(path, monitor);
				JavaLanguageServerPlugin.logInfo("End index project: " + path.toPortableString());
			} catch (Exception ex) {
				// ignore it
			} finally {
				// Output model
				try {
					Path projectPath = Paths.get(path.toFile().toURI());
					FileUtils.writeStringToFile(projectPath.resolve(IConstant.DEFAULT_LSIF_FILE_NAME).toFile(), gson.toJson(emitter.getElements()));
				} catch (IOException e) {
				}
			}
		}
	}

	private void buildIndex(IPath path, IProgressMonitor monitor, Emitter emitter) {

		JavaLsif lsif = new JavaLsif();
		IProject[] projects = ResourcesPlugin.getWorkspace().getRoot().getProjects();
		LanguageServerIndexerPlugin.logInfo(String.format("collectModel, projects # = %d", projects.length));

		for (IProject proj : projects) {
			if (proj == null) {
				return;
			}

			emitter.emit(lsif.getVertexBuilder().metaData("0.1.0"));

			IJavaProject javaProject = JavaCore.create(proj);
			try {
				Project projVertex = lsif.getVertexBuilder().project();
				emitter.emit(projVertex);
				IClasspathEntry[] references = javaProject.getRawClasspath();
				for (IClasspathEntry reference : references) {
					if (reference.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
						IPackageFragmentRoot[] fragmentRoots = javaProject.findPackageFragmentRoots(reference);
						for (IPackageFragmentRoot fragmentRoot : fragmentRoots) {
							for (IJavaElement child : fragmentRoot.getChildren()) {
								IPackageFragment fragment = (IPackageFragment) child;
								if (fragment.hasChildren()) {
									for (IJavaElement sourceFile : fragment.getChildren()) {
										CompilationUnit cu = ASTUtil.createAST((ITypeRoot) sourceFile, monitor);
										Document docVertex = lsif.getVertexBuilder()
												.document(ResourceUtils.fixURI(sourceFile.getResource().getRawLocationURI()));
										emitter.emit(docVertex);
										emitter.emit(lsif.getEdgeBuilder().contains(projVertex, docVertex));

										// Document symbols
										List<DocumentSymbol> symbols = DocumentSymbolHandler.handle(docVertex.getUri());
										DocumentSymbolResult documentSymbolResult = lsif.getVertexBuilder().documentSymbolResult(symbols);
										emitter.emit(documentSymbolResult);
										emitter.emit(lsif.getEdgeBuilder().documentSymbols(docVertex, documentSymbolResult));

										cu.accept(new LsifVisitor((new IndexerContext(emitter, lsif, docVertex, (ITypeRoot) sourceFile))));
									}
								}
							}
						}
					}
				}
			} catch (Exception e) {
			}
		}
	}

	private void initializeJdtls() {
		Map<String, Object> extendedClientCapabilities = new HashMap<>();
		extendedClientCapabilities.put("classFileContentsSupport", false);
		JavaLanguageServerPlugin.getPreferencesManager().updateClientPrefences(new ClientCapabilities(), extendedClientCapabilities);
	}
}