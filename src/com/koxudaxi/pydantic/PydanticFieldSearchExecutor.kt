package com.koxudaxi.pydantic

import com.intellij.openapi.application.QueryExecutorBase
import com.intellij.openapi.application.ReadAction.run
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiReference
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.jetbrains.python.psi.PyCallExpression
import com.jetbrains.python.psi.PyClass
import com.jetbrains.python.psi.PyKeywordArgument
import com.jetbrains.python.psi.PyTargetExpression
import com.jetbrains.python.psi.search.PyClassInheritorsSearch

private fun searchField(pyClass: PyClass, elementName: String, consumer: Processor<in PsiReference>): Boolean {
    if (!isPydanticModel(pyClass)) return false
    val pyTargetExpression = pyClass.findClassAttribute(elementName, false, null) ?: return false
    consumer.process(pyTargetExpression.reference)
    return true
}

private fun searchKeywordArgument(pyClass: PyClass, elementName: String, consumer: Processor<in PsiReference>) {
    if (!isPydanticModel(pyClass)) return
    ReferencesSearch.search(pyClass as PsiElement).forEach { psiReference ->
        val callee = PsiTreeUtil.getParentOfType(psiReference.element, PyCallExpression::class.java)
        callee?.arguments?.forEach { argument ->
            if (argument is PyKeywordArgument && argument.name == elementName) {
                consumer.process(argument.reference)

            }
        }
    }
}

private fun searchDirectReferenceField(pyClass: PyClass, elementName: String, consumer: Processor<in PsiReference>): Boolean {
    if (searchField(pyClass, elementName, consumer)) return true

    pyClass.getAncestorClasses(null).forEach { ancestorClass ->
        if (!isPydanticBaseModel(ancestorClass)) {
            if (isPydanticModel(ancestorClass)) {
                if (searchDirectReferenceField(ancestorClass, elementName, consumer)) {
                    return true
                }
            }
        }
    }
    return false
}

private fun searchAllElementReference(pyClass: PyClass, elementName: String, added: MutableSet<PyClass>, consumer: Processor<in PsiReference>) {
    added.add(pyClass)
    searchField(pyClass, elementName, consumer)
    searchKeywordArgument(pyClass, elementName, consumer)
    pyClass.getAncestorClasses(null).forEach { ancestorClass ->
        if (isPydanticBaseModel(ancestorClass) && !added.contains(ancestorClass)) {
            searchField(pyClass, elementName, consumer)
        }
    }
    PyClassInheritorsSearch.search(pyClass, true).forEach { inheritorsPyClass ->
        if (!isPydanticBaseModel(inheritorsPyClass) && !added.contains(inheritorsPyClass)) {
            searchAllElementReference(inheritorsPyClass, elementName, added, consumer)
        }
    }
}

class PydanticFieldSearchExecutor : QueryExecutorBase<PsiReference, ReferencesSearch.SearchParameters>() {
    override fun processQuery(queryParameters: ReferencesSearch.SearchParameters, consumer: Processor<in PsiReference>) {

        when (val element = queryParameters.elementToSearch) {
            is PyKeywordArgument -> run<RuntimeException> {
                val elementName = element.name ?: return@run
                val pyClass = getPyClassByPyKeywordArgument(element) ?: return@run
                if (!isPydanticModel(pyClass)) return@run
                searchDirectReferenceField(pyClass, elementName, consumer)
            }
            is PyTargetExpression -> run<RuntimeException> {
                val elementName = element.name ?: return@run
                val pyClass = element.containingClass ?: return@run
                if (!isPydanticModel(pyClass)) return@run
                searchAllElementReference(pyClass, elementName, mutableSetOf(), consumer)
            }
        }
    }
}
