package org.revenj.serialization.json;

import com.dslplatform.json.*;
import org.revenj.TreePath;
import org.revenj.patterns.ServiceLocator;
import org.revenj.serialization.Serialization;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Optional;

public class DslJsonSerialization extends DslJson<ServiceLocator> implements Serialization<String> {

	private static DslJson.Settings<ServiceLocator> buildSettings(ServiceLocator locator, Fallback<ServiceLocator> fallback) {
		return com.dslplatform.json.runtime.Settings.<ServiceLocator>withRuntime()
				.withContext(locator)
				.fallbackTo(fallback)
				.withJavaConverters(true)
				.skipDefaultValues(false)
				.includeServiceLoader();
	}

	public DslJsonSerialization(final ServiceLocator locator, Optional<Fallback<ServiceLocator>> fallback) {
		super(buildSettings(locator, fallback.orElse(null)));
		registerReader(TreePath.class, TreePathConverter.Reader);
		registerWriter(TreePath.class, TreePathConverter.Writer);
	}

	@Override
	public String serialize(Type manifest, Object value) throws IOException {
		if (value == null) return "null";
		if (manifest == null) {
			manifest = value.getClass();
		}
		final JsonWriter jw = newWriter();
		if (!serialize(jw, manifest, value)) {
			if (fallback != null) {
				ByteArrayOutputStream os = new ByteArrayOutputStream();
				fallback.serialize(value, os);
				return os.toString("UTF-8");
			}
			throw new IOException("Unable to serialize provided object. Failed to find serializer for: " + manifest);
		}
		return jw.toString();
	}

	@Override
	public Object deserialize(Type type, String data) throws IOException {
		byte[] bytes = data.getBytes("UTF-8");
		return super.deserialize(type, bytes, bytes.length);
	}
}
