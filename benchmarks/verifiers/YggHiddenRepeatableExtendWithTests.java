package org.junit.jupiter.engine.descriptor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.Extension;
import org.junit.jupiter.engine.extension.ExtensionRegistrar;

class YggHiddenRepeatableExtendWithTests {

	@Test
	void registersRepeatableMetaAnnotationsFromStaticFields() {
		ExtensionRegistrar registrar = mock();
		ExtensionUtils.registerExtensionsFromStaticFields(registrar, TestCase.class);
		verify(registrar).registerExtension(Extension1.class);
		verify(registrar).registerExtension(Extension2.class);
	}

	@Test
	void registersRepeatableMetaAnnotationsFromInstanceFields() {
		Class<TestCase> testClass = TestCase.class;
		ExtensionRegistrar registrar = mock();
		ExtensionUtils.registerExtensionsFromInstanceFields(registrar, testClass);
		verify(registrar).registerExtension(Extension1.class);
		verify(registrar).registerExtension(Extension2.class);
	}

	static class Extension1 implements Extension {
	}

	static class Extension2 implements Extension {
	}

	@Retention(RetentionPolicy.RUNTIME)
	@ExtendWith(Extension1.class)
	@ExtendWith(Extension2.class)
	@interface UseCustomExtensions {
	}

	static class TestCase {

		@UseCustomExtensions
		static Object staticField;

		@UseCustomExtensions
		Object instanceField;
	}
}
