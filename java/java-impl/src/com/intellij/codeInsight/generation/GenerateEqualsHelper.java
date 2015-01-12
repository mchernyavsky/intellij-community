/*
 * Copyright 2000-2009 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInsight.generation;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.MethodSignature;
import com.intellij.psi.util.MethodSignatureUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.HashMap;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.java.generate.GenerationUtil;

import java.util.*;

/**
 * @author dsl
 */
public class GenerateEqualsHelper implements Runnable {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInsight.generation.GenerateEqualsHelper");
  private final PsiClass myClass;
  private final PsiField[] myEqualsFields;
  private final PsiField[] myHashCodeFields;
  private final HashSet<PsiField> myNonNullSet;
  private final PsiElementFactory myFactory;
  private final boolean mySuperHasHashCode;
  private final CodeStyleManager myCodeStyleManager;
  private final JavaCodeStyleManager myJavaCodeStyleManager;
  private final Project myProject;
  private final boolean myCheckParameterWithInstanceof;

  public GenerateEqualsHelper(Project project,
                              PsiClass aClass,
                              PsiField[] equalsFields,
                              PsiField[] hashCodeFields,
                              PsiField[] nonNullFields,
                              boolean useInstanceofToCheckParameterType) {
    myClass = aClass;
    myEqualsFields = equalsFields;
    myHashCodeFields = hashCodeFields;
    myProject = project;
    myCheckParameterWithInstanceof = useInstanceofToCheckParameterType;

    myNonNullSet = new HashSet<PsiField>();
    ContainerUtil.addAll(myNonNullSet, nonNullFields);
    final PsiManager manager = PsiManager.getInstance(project);

    myFactory = JavaPsiFacade.getInstance(manager.getProject()).getElementFactory();

    mySuperHasHashCode = superMethodExists(getHashCodeSignature());
    myCodeStyleManager = CodeStyleManager.getInstance(manager.getProject());
    myJavaCodeStyleManager = JavaCodeStyleManager.getInstance(manager.getProject());
  }

  private static boolean shouldAddOverrideAnnotation(PsiElement context) {
    CodeStyleSettings style = CodeStyleSettingsManager.getSettings(context.getProject());

    return style.INSERT_OVERRIDE_ANNOTATION && PsiUtil.isLanguageLevel5OrHigher(context);
  }

  @Override
  public void run() {
    try {
      final Collection<PsiMethod> members = generateMembers();
      for (PsiElement member : members) {
        myClass.add(member);
      }
    }
    catch (IncorrectOperationException e) {
      LOG.error(e);
    }
  }

  public Collection<PsiMethod> generateMembers() throws IncorrectOperationException {
    PsiMethod equals = null;
    if (myEqualsFields != null && findMethod(myClass, getEqualsSignature(myProject, myClass.getResolveScope())) == null) {
      equals = createEquals();
    }

    PsiMethod hashCode = null;
    if (myHashCodeFields != null && findMethod(myClass, getHashCodeSignature()) == null) {
      if (myHashCodeFields.length > 0) {
        hashCode = createHashCode();
      }
      else {
        if (!mySuperHasHashCode) {
          @NonNls String text = "";
          if (shouldAddOverrideAnnotation(myClass)) {
            text += "@Override\n";
          }

          text += "public int hashCode() {\nreturn 0;\n}";
          final PsiMethod trivialHashCode = myFactory.createMethodFromText(text, null);
          hashCode = (PsiMethod)myCodeStyleManager.reformat(trivialHashCode);
        }
      }
    }
    if (hashCode != null && equals != null) {
      return Arrays.asList(equals, hashCode);
    }
    else if (equals != null) {
      return Collections.singletonList(equals);
    }
    else if (hashCode != null) {
      return Collections.singletonList(hashCode);
    }
    else {
      return Collections.emptyList();
    }
  }


  private PsiMethod createEquals() throws IncorrectOperationException {
    @NonNls StringBuilder buffer = new StringBuilder();
    CodeStyleSettings styleSettings = CodeStyleSettingsManager.getSettings(myProject);
    if (shouldAddOverrideAnnotation(myClass)) {
      buffer.append("@Override\n");
    }
    ArrayList<PsiField> equalsFields = new ArrayList<PsiField>();
    ContainerUtil.addAll(equalsFields, myEqualsFields);
    Collections.sort(equalsFields, EqualsFieldsComparator.INSTANCE);

    final HashMap<String, Object> contextMap = new HashMap<String, Object>();

    final PsiType classType = JavaPsiFacade.getElementFactory(myClass.getProject()).createType(myClass);
    final JavaCodeStyleManager codeStyleManager = JavaCodeStyleManager.getInstance(myClass.getProject());
    String[] nameSuggestions = codeStyleManager
      .suggestVariableName(VariableKind.LOCAL_VARIABLE, null, null, classType).names;
    String instanceBaseName = nameSuggestions.length > 0 && nameSuggestions[0].length() < 10 ? nameSuggestions[0] : "that";
    contextMap.put("instanceName", instanceBaseName);

    final PsiType objectType = PsiType.getJavaLangObject(myClass.getManager(), myClass.getResolveScope());
    nameSuggestions = codeStyleManager.suggestVariableName(VariableKind.PARAMETER, null, null, objectType).names;
    final String objectBaseName = nameSuggestions.length > 0 ? nameSuggestions[0] : "object";
    contextMap.put("baseParamName", objectBaseName);
    contextMap.put("superHasEquals", superMethodExists(getEqualsSignature(myProject, myClass.getResolveScope())));
    contextMap.put("checkParameterWithInstanceof", myCheckParameterWithInstanceof);

    final String methodText = GenerationUtil
      .velocityGenerateCode(myClass, equalsFields, myNonNullSet, new HashMap<String, String>(), contextMap,
                            EqualsHashCodeTemplatesManager.getInstance().getDefaultEqualsTemplate().getTemplate(), 0, false);
    buffer.append(methodText);
    PsiMethod result;
    try {
      result = myFactory.createMethodFromText(buffer.toString(), myClass);
    }
    catch (IncorrectOperationException e) {
      return null;
    }
    final PsiParameter[] parameters = result.getParameterList().getParameters();
    if (parameters.length != 1) return null;
    final PsiParameter parameter = parameters[0];
    PsiUtil.setModifierProperty(parameter, PsiModifier.FINAL, styleSettings.GENERATE_FINAL_PARAMETERS);

    PsiMethod method = (PsiMethod)myCodeStyleManager.reformat(result);
    method = (PsiMethod)myJavaCodeStyleManager.shortenClassReferences(method);
    return method;
  }

  private boolean superMethodExists(MethodSignature methodSignature) {
    LOG.assertTrue(myClass.isValid());
    PsiMethod superEquals = MethodSignatureUtil.findMethodBySignature(myClass, methodSignature, true);
    if (superEquals == null) return true;
    if (superEquals.hasModifierProperty(PsiModifier.ABSTRACT)) return false;
    return !CommonClassNames.JAVA_LANG_OBJECT.equals(superEquals.getContainingClass().getQualifiedName());
  }

  private PsiMethod createHashCode() throws IncorrectOperationException {
    @NonNls StringBuilder buffer = new StringBuilder();

    if (shouldAddOverrideAnnotation(myClass)) {
      buffer.append("@Override\n");
    }

    final HashMap<String, Object> contextMap = new HashMap<String, Object>();
    contextMap.put("superHasHashCode", mySuperHasHashCode);

    final String methodText = GenerationUtil
      .velocityGenerateCode(myClass, Arrays.asList(myHashCodeFields), myNonNullSet, new HashMap<String, String>(), contextMap, 
                            EqualsHashCodeTemplatesManager.getInstance().getDefaultHashcodeTemplate().getTemplate(), 0, false);
    buffer.append(methodText);
    PsiMethod hashCode;
    try {
      hashCode = myFactory.createMethodFromText(buffer.toString(), null);
    }
    catch (IncorrectOperationException e) {
      return null;
    }
    hashCode = (PsiMethod)myJavaCodeStyleManager.shortenClassReferences(hashCode);
    return (PsiMethod)myCodeStyleManager.reformat(hashCode);
  }


  public void invoke() {
    ApplicationManager.getApplication().runWriteAction(this);
  }

  static PsiMethod findMethod(PsiClass aClass, MethodSignature signature) {
    return MethodSignatureUtil.findMethodBySignature(aClass, signature, false);
  }

  static class EqualsFieldsComparator implements Comparator<PsiField> {
    public static final EqualsFieldsComparator INSTANCE = new EqualsFieldsComparator();

    @Override
    public int compare(PsiField f1, PsiField f2) {
      if (f1.getType() instanceof PsiPrimitiveType && !(f2.getType() instanceof PsiPrimitiveType)) return -1;
      if (!(f1.getType() instanceof PsiPrimitiveType) && f2.getType() instanceof PsiPrimitiveType) return 1;
      final String name1 = f1.getName();
      final String name2 = f2.getName();
      assert name1 != null && name2 != null;
      return name1.compareTo(name2);
    }
  }

  public static boolean isArrayOfObjects(PsiType aType) {
    if (!(aType instanceof PsiArrayType)) return false;
    final PsiType componentType = ((PsiArrayType)aType).getComponentType();
    final PsiClass psiClass = PsiUtil.resolveClassInType(componentType);
    if (psiClass == null) return false;
    final String qName = psiClass.getQualifiedName();
    return CommonClassNames.JAVA_LANG_OBJECT.equals(qName);
  }

  public static MethodSignature getHashCodeSignature() {
    return MethodSignatureUtil.createMethodSignature("hashCode", PsiType.EMPTY_ARRAY, PsiTypeParameter.EMPTY_ARRAY, PsiSubstitutor.EMPTY);
  }

  public static MethodSignature getEqualsSignature(Project project, GlobalSearchScope scope) {
    final PsiClassType javaLangObject = PsiType.getJavaLangObject(PsiManager.getInstance(project), scope);
    return MethodSignatureUtil
      .createMethodSignature("equals", new PsiType[]{javaLangObject}, PsiTypeParameter.EMPTY_ARRAY, PsiSubstitutor.EMPTY);
  }
}
