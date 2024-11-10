package com.github.kam1k4dze.kaijuPycharmPlugin

import com.intellij.json.JsonFileType
import com.intellij.json.psi.JsonProperty
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.patterns.PlatformPatterns
import com.intellij.psi.*
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.ProcessingContext
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.stubs.PyClassNameIndex
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Contributes reference providers for YAML service definitions and variable references.
 * Handles class references and configuration variable resolution.
 */
class KaijuYamlReferenceContributor : PsiReferenceContributor() {
    private companion object {
        private val LOG = Logger.getInstance(KaijuYamlReferenceContributor::class.java)
    }

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        LOG.debug("Registering YAML reference providers")
        registerServiceProviders(registrar)
        registerVariableProviders(registrar)
    }

    private fun registerServiceProviders(registrar: PsiReferenceRegistrar) {
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement()
                .inside(YAMLKeyValue::class.java)
                .andOr(
                    PlatformPatterns.psiElement().withParent(YAMLKeyValue::class.java),
                    PlatformPatterns.psiElement().withParent(YAMLSequenceItem::class.java)
                ),
            KaijuServiceReferenceProvider()
        )
    }

    private fun registerVariableProviders(registrar: PsiReferenceRegistrar) {
        // Updated to match any YAMLKeyValue
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement()
                .inside(YAMLKeyValue::class.java),
            KaijuVariableReferenceProvider()
        )
    }
}

/**
 * Provides references for service class declarations in YAML.
 */
class KaijuServiceReferenceProvider : PsiReferenceProvider() {
    private companion object {
        private val LOG = Logger.getInstance(KaijuServiceReferenceProvider::class.java)
        private const val CLASS_KEY = "cls"
    }

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext
    ): Array<PsiReference> {
        LOG.debug("Processing service references for: ${element.text}")

        if (!isValidServiceReference(element)) {
            return PsiReference.EMPTY_ARRAY
        }

        return arrayOf(KaijuServiceReference(element))
    }

    private fun isValidServiceReference(element: PsiElement): Boolean = when (element.parent) {
        is YAMLKeyValue -> (element.parent as YAMLKeyValue).keyText == CLASS_KEY
        is YAMLSequenceItem -> true
        else -> false
    }
}

/**
 * Reference implementation for Python service classes.
 */
class KaijuServiceReference(element: PsiElement) : PsiReferenceBase<PsiElement>(element) {
    private companion object {
        private val LOG = Logger.getInstance(KaijuServiceReference::class.java)
    }

    override fun resolve(): PsiElement? {
        val className = element.text.cleanClassName()
        LOG.debug("Resolving service reference for: $className")
        return element.project.findPythonClass(className)
    }

    override fun getVariants(): Array<Any> = emptyArray()

    private fun String.cleanClassName(): String =
        trim().removeSurrounding("\"").removeSurrounding("'")

    private fun Project.findPythonClass(className: String): PyClass? =
        PyClassNameIndex.find(className, this, GlobalSearchScope.allScope(this))
            .firstOrNull()
}

/**
 * Provides references for variable declarations in settings.
 */
class KaijuVariableReferenceProvider : PsiReferenceProvider() {
    private companion object {
        private val LOG = Logger.getInstance(KaijuVariableReferenceProvider::class.java)
        private val VARIABLE_REGEX = Regex("\\[.*\\]")
    }

    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext
    ): Array<PsiReference> {
        val text = element.text.trim().removeSurrounding("\"").removeSurrounding("'")
        return if (text.matches(VARIABLE_REGEX)) {
            arrayOf(KaijuVariableReference(element))
        } else {
            PsiReference.EMPTY_ARRAY
        }
    }
}

/**
 * Reference implementation for configuration variables.
 */
class KaijuVariableReference(element: PsiElement) : PsiReferenceBase<PsiElement>(element) {
    private companion object {
        private val LOG = Logger.getInstance(KaijuVariableReference::class.java)
        private val VARIABLE_PATTERN = Regex("\\[(.*?)(?::.+)?\\]")
    }

    override fun resolve(): PsiElement? {
        val variableName = element.text.extractVariableName()
        LOG.debug("Resolving variable reference: $variableName")
        return findJsonProperty(element.project, variableName)
    }

    override fun getVariants(): Array<Any> = emptyArray()

    private fun String.extractVariableName(): String =
        VARIABLE_PATTERN.find(trim().removeSurrounding("\"").removeSurrounding("'"))
            ?.groupValues?.get(1) ?: ""

    private fun findJsonProperty(project: Project, propertyName: String): JsonProperty? =
        FileTypeIndex.getFiles(JsonFileType.INSTANCE, GlobalSearchScope.projectScope(project))
            .asSequence()
            .mapNotNull { findPropertyInFile(project, it, propertyName) }
            .firstOrNull()

    private fun findPropertyInFile(project: Project, file: VirtualFile, propertyName: String): JsonProperty? =
        PsiManager.getInstance(project).findFile(file)?.let { psiFile ->
            PsiTreeUtil.findChildrenOfType(psiFile, JsonProperty::class.java)
                .find { it.name == propertyName }
        }
}