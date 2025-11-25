package com.github.kam1k4dze.kaijupycharmplugin

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.json.JsonFileType
import com.intellij.json.psi.JsonFile
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
import com.jetbrains.python.psi.stubs.PyClassNameIndex
import org.jetbrains.yaml.YAMLFileType
import org.jetbrains.yaml.psi.YAMLKeyValue
import org.jetbrains.yaml.psi.YAMLScalar
import org.jetbrains.yaml.psi.YAMLSequenceItem

/**
 * Data class representing a parsed variable reference
 */
data class VariableReference(
    val name: String,
    val defaultValue: String?,
    val hasDefault: Boolean = defaultValue != null
) {
    companion object {
        private val VARIABLE_PATTERN = Regex("\\[(.*?)(?::(.+))?\\]")

        fun fromText(text: String): VariableReference? {
            val match = VARIABLE_PATTERN.find(text.trim().removeSurrounding("\"").removeSurrounding("'"))
                ?: return null

            val name = match.groupValues[1]
            val defaultValue = match.groupValues.getOrNull(2)?.takeIf { it.isNotEmpty() }

            return VariableReference(name, defaultValue)
        }
    }
}

/**
 * Service for managing environment JSON files and variable lookups
 */
object KaijuConfigService {
    private val LOG = Logger.getInstance(KaijuConfigService::class.java)
    private val ENV_FILE_PATTERN = Regex("env\\..+\\.json")

    fun getEnvJsonFiles(project: Project): Collection<VirtualFile> =
        FileTypeIndex.getFiles(JsonFileType.INSTANCE, GlobalSearchScope.projectScope(project))
            .filter { it.name.matches(ENV_FILE_PATTERN) }

    fun getYamlFiles(project: Project): Collection<VirtualFile> =
        FileTypeIndex.getFiles(YAMLFileType.YML, GlobalSearchScope.projectScope(project))

    fun findJsonProperty(project: Project, propertyName: String): JsonProperty? =
        getEnvJsonFiles(project)
            .asSequence()
            .mapNotNull { file ->
                PsiManager.getInstance(project).findFile(file)?.let { psiFile ->
                    PsiTreeUtil.findChildrenOfType(psiFile, JsonProperty::class.java)
                        .find { it.name == propertyName }
                }
            }
            .firstOrNull()

    fun getAllJsonProperties(project: Project): Set<String> =
        getEnvJsonFiles(project)
            .flatMap { file ->
                PsiManager.getInstance(project).findFile(file)?.let { psiFile ->
                    PsiTreeUtil.findChildrenOfType(psiFile, JsonProperty::class.java)
                        .mapNotNull { it.name }
                } ?: emptyList()
            }
            .toSet()

    fun getAllYamlVariableReferences(project: Project): Set<String> =
        getYamlFiles(project)
            .flatMap { file ->
                PsiManager.getInstance(project).findFile(file)?.let { psiFile ->
                    PsiTreeUtil.findChildrenOfType(psiFile, YAMLScalar::class.java)
                        .mapNotNull { scalar ->
                            VariableReference.fromText(scalar.textValue)?.name
                        }
                } ?: emptyList()
            }
            .toSet()
}

/**
 * Reference contributor for YAML service definitions and variable references
 */
class KaijuYamlReferenceContributor : PsiReferenceContributor() {
    private companion object {
        private val LOG = Logger.getInstance(KaijuYamlReferenceContributor::class.java)
    }

    override fun registerReferenceProviders(registrar: PsiReferenceRegistrar) {
        LOG.debug("Registering YAML reference providers")

        // Service class references
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement()
                .inside(YAMLKeyValue::class.java)
                .andOr(
                    PlatformPatterns.psiElement().withParent(YAMLKeyValue::class.java),
                    PlatformPatterns.psiElement().withParent(YAMLSequenceItem::class.java)
                ),
            KaijuServiceReferenceProvider()
        )

        // Variable references
        registrar.registerReferenceProvider(
            PlatformPatterns.psiElement()
                .inside(YAMLKeyValue::class.java),
            KaijuVariableReferenceProvider()
        )
    }
}

/**
 * Provides references for service class declarations in YAML
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
 * Reference implementation for Python service classes
 */
class KaijuServiceReference(element: PsiElement) : PsiReferenceBase<PsiElement>(element) {
    override fun resolve(): PsiElement? {
        val className = element.text.trim().removeSurrounding("\"").removeSurrounding("'")
        return PyClassNameIndex.find(className, element.project, GlobalSearchScope.allScope(element.project))
            .firstOrNull()
    }

    override fun getVariants(): Array<Any> = emptyArray()
}

/**
 * Provides references for configuration variable declarations
 */
class KaijuVariableReferenceProvider : PsiReferenceProvider() {
    override fun getReferencesByElement(
        element: PsiElement,
        context: ProcessingContext
    ): Array<PsiReference> {
        val variableRef = VariableReference.fromText(element.text)
        return if (variableRef != null) {
            arrayOf(KaijuVariableReference(element, variableRef))
        } else {
            PsiReference.EMPTY_ARRAY
        }
    }
}

/**
 * Reference implementation for configuration variables
 */
class KaijuVariableReference(
    element: PsiElement,
    private val variableRef: VariableReference
) : PsiReferenceBase<PsiElement>(element) {

    override fun resolve(): PsiElement? =
        KaijuConfigService.findJsonProperty(element.project, variableRef.name)

    override fun getVariants(): Array<Any> = emptyArray()
}

/**
 * Inspection for missing variable values in YAML files
 */
class KaijuMissingVariableInspection : LocalInspectionTool() {

    override fun getShortName(): String = "KaijuMissingVariable"

    override fun getDisplayName(): String = "Kaiju missing variable value"

    override fun getGroupDisplayName(): String = "Kaiju Configuration"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)

                if (element is YAMLScalar) {
                    checkYamlVariable(element, holder)
                }
            }
        }
    }

    private fun checkYamlVariable(element: YAMLScalar, holder: ProblemsHolder) {
        val variableRef = VariableReference.fromText(element.textValue) ?: return

        // Only check variables without default values
        if (!variableRef.hasDefault) {
            val jsonProperty = KaijuConfigService.findJsonProperty(element.project, variableRef.name)
            if (jsonProperty == null) {
                holder.registerProblem(
                    element,
                    "Variable '${variableRef.name}' has no value defined in env.*.json files and no default value",
                    ProblemHighlightType.ERROR
                )
            }
        }
    }
}

/**
 * Inspection for unused JSON properties in env files
 */
class KaijuUnusedJsonPropertyInspection : LocalInspectionTool() {

    override fun getShortName(): String = "KaijuUnusedJsonProperty"

    override fun getDisplayName(): String = "Kaiju unused JSON property"

    override fun getGroupDisplayName(): String = "Kaiju Configuration"

    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitor {
        return object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                super.visitElement(element)

                if (element is JsonProperty) {
                    checkJsonProperty(element, holder)
                }
            }
        }
    }

    private fun checkJsonProperty(element: JsonProperty, holder: ProblemsHolder) {
        val file = element.containingFile
        if (file !is JsonFile || !file.name.matches(Regex("env\\..+\\.json"))) {
            return
        }

        val propertyName = element.name ?: return
        val yamlVariableReferences = KaijuConfigService.getAllYamlVariableReferences(element.project)

        if (propertyName !in yamlVariableReferences) {
            holder.registerProblem(
                element.firstChild, // Point to the key
                "Property '$propertyName' is not referenced in any YAML configuration file",
                ProblemHighlightType.WARNING
            )
        }
    }
}