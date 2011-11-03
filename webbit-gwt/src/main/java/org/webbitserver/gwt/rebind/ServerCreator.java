/**
 *  Copyright 2011 Colin Alworth
 * 
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */
package org.webbitserver.gwt.rebind;

import java.io.PrintWriter;

import org.webbitserver.gwt.client.impl.ServerImpl;
import org.webbitserver.gwt.shared.Client;
import org.webbitserver.gwt.shared.Server;
import org.webbitserver.gwt.shared.impl.ClientInvocation;
import org.webbitserver.gwt.shared.impl.ServerInvocation;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.ext.GeneratorContext;
import com.google.gwt.core.ext.GeneratorContextExt;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.TreeLogger.Type;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.core.ext.typeinfo.JClassType;
import com.google.gwt.core.ext.typeinfo.JMethod;
import com.google.gwt.core.ext.typeinfo.JParameter;
import com.google.gwt.core.ext.typeinfo.TypeOracle;
import com.google.gwt.dev.util.Name;
import com.google.gwt.editor.rebind.model.ModelUtils;
import com.google.gwt.user.client.rpc.RemoteServiceRelativePath;
import com.google.gwt.user.client.rpc.impl.Serializer;
import com.google.gwt.user.rebind.ClassSourceFileComposerFactory;
import com.google.gwt.user.rebind.SourceWriter;
import com.google.gwt.user.rebind.rpc.SerializableTypeOracleBuilder;
import com.google.gwt.user.rebind.rpc.TypeSerializerCreator;

/**
 * Creates a new instance of the given Server type
 *
 */
public class ServerCreator {
	private final JClassType serverType;
	/**
	 * 
	 */
	public ServerCreator(JClassType serverType) {
		this.serverType = serverType;
	}

	public void create(TreeLogger logger, GeneratorContext context) throws UnableToCompleteException {
		String typeName = this.serverType.getQualifiedSourceName();

		String packageName = getPackageName();
		String simpleName = getSimpleName();

		TypeOracle oracle = context.getTypeOracle();

		PrintWriter pw = context.tryCreate(logger, packageName, simpleName);
		if (pw == null) {
			return;
		}
		JClassType serverType = oracle.findType(Name.getSourceNameForClass(Server.class));
		JClassType clientType = ModelUtils.findParameterizationOf(serverType, this.serverType)[1];

		ClassSourceFileComposerFactory factory = new ClassSourceFileComposerFactory(packageName, simpleName);
		factory.setSuperclass(Name.getSourceNameForClass(ServerImpl.class) + "<" + typeName + "," + clientType.getQualifiedSourceName() + ">");
		factory.addImplementedInterface(typeName);

		SourceWriter sw = factory.createSourceWriter(context, pw);

		//TODO move this check before the printwriter creation can fail, and allow the warn to be optional
		RemoteServiceRelativePath path = this.serverType.getAnnotation(RemoteServiceRelativePath.class);
		if (path == null) {
			logger.log(Type.WARN, "@RemoteServiceRelativePath required on " + typeName + " to make a connection to the server without a ServerBuilder");
			throw new UnableToCompleteException();
		} else {
			sw.println("public %1$s() {", simpleName);
			sw.indentln("super(\"ws://\", com.google.gwt.user.client.Window.Location.getHost(), \"%1$s\");", path.value());
			sw.println("}");
		}

		sw.println("public %1$s(String url) {", simpleName);
		sw.indentln("super(url);");
		sw.println("}");

		//Find all types that may go over the wire
		SerializableTypeOracleBuilder serverSerializerBuilder = new SerializableTypeOracleBuilder(logger, context.getPropertyOracle(), (GeneratorContextExt) context);
		appendMethodParameters(logger, this.serverType, Client.class, serverSerializerBuilder);
		serverSerializerBuilder.addRootType(logger, oracle.findType(ClientInvocation.class.getName()));

		SerializableTypeOracleBuilder clientSerializerBuilder = new SerializableTypeOracleBuilder(logger, context.getPropertyOracle(), (GeneratorContextExt) context);
		appendMethodParameters(logger, clientType, Server.class, clientSerializerBuilder);
		clientSerializerBuilder.addRootType(logger, oracle.findType(ServerInvocation.class.getName()));

		String tsName = simpleName + "_TypeSerializer";
		TypeSerializerCreator serializerCreator = new TypeSerializerCreator(logger, clientSerializerBuilder.build(logger), serverSerializerBuilder.build(logger), (GeneratorContextExt) context, packageName + "." + tsName, tsName);
		serializerCreator.realize(logger);

		// Make the newly created Serializer available at runtime
		sw.println("protected %1$s __getSerializer() {", Serializer.class.getName());
		sw.indentln("return %2$s.<%1$s>create(%1$s.class);", tsName, GWT.class.getName());
		sw.println("}");

		// Build methods that call from the client to the server
		for (JMethod m : this.serverType.getInheritableMethods()) {
			if (isRemoteMethod(m, Server.class)) {
				printServerMethodBody(logger, context, sw, m);
			}
		}


		// Read incoming calls and dispatch them to the correct client method
		sw.println("protected void __invoke(String method, Object[] params) {");
		for (JMethod m : clientType.getInheritableMethods()) {
			if (isRemoteMethod(m, Client.class)) {
				JParameter[] params = m.getParameters();
				sw.println("if (method.equals(\"%1$s\") && params.length == %2$d) {", m.getName(), params.length);
				sw.indent();
				sw.println("getClient().%1$s(", m.getName());
				sw.indent();
				for (int i = 0; i < params.length; i++) {
					if (i != 0) {
						sw.print(",");
					}
					sw.println("(%1$s)params[%2$d]", params[i].getType().getQualifiedSourceName(), i);
				}
				sw.outdent();
				sw.println(");");
				sw.outdent();
				sw.println("}");
			}
		}
		sw.println("}");


		sw.println("protected void __onError(Exception error) {");
		sw.println("}");

		sw.commit(logger);
	}

	public String getPackageName() {
		return serverType.getPackage().getName();
	}
	public String getSimpleName() {
		return serverType.getName().replace('.', '_') + "_ProxyImpl";
	}

	public String getQualifiedSourceName() {
		return getPackageName() + "." + getSimpleName();
	}



	/**
	 * Helper method to build up the list of types that can go over the wire
	 * @param logger
	 * @param toGenerate
	 * @param superClass
	 * @param clientSerializerBuilder
	 */
	private void appendMethodParameters(TreeLogger logger, JClassType toGenerate,
			Class<?> superClass, SerializableTypeOracleBuilder clientSerializerBuilder) {
		for (JMethod m : toGenerate.getMethods()) {
			if (isRemoteMethod(m, superClass)) {
				for (JParameter param : m.getParameters()) {
					clientSerializerBuilder.addRootType(logger, param.getType());
				}
			}
		}
	}

	/**
	 * Writes out the method to use to invoke a server call. Mostly derived from RPC's way of building proxy methods
	 * 
	 * @param logger
	 * @param context
	 * @param sw
	 * @param m
	 */
	private void printServerMethodBody(TreeLogger logger, GeneratorContext context,
			SourceWriter sw, JMethod m) {
		sw.println("%1$s {", m.getReadableDeclaration(false, true, true, true, true));
		sw.indent();

		sw.println("__sendMessage(\"%1$s\"", m.getName());
		for (JParameter param : m.getParameters()) {
			sw.indentln(", %1$s", param.getName());
		}
		sw.println(");");

		sw.outdent();
		sw.println("}");
	}

	/**
	 * Checks to see if the given method can be called over the wire.
	 * 
	 * 
	 * @param m method to check
	 * @param superClass either {@link Server} or {@link Client}, indicating which direction the call will be made
	 * @return
	 */
	private boolean isRemoteMethod(JMethod m, Class<?> superClass) {
		assert superClass == Server.class || superClass == Client.class;
		return !m.getEnclosingType().getQualifiedSourceName().equals(superClass.getName());
	}
}
