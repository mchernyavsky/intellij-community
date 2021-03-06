// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.util.gotoByName

import com.intellij.ide.ui.search.BooleanOptionDescription
import com.intellij.ide.ui.search.OptionDescription
import com.intellij.ide.util.gotoByName.GotoActionModel.ActionWrapper
import com.intellij.ide.util.gotoByName.GotoActionModel.MatchMode
import com.intellij.ide.util.gotoByName.GotoActionModel.MatchedValue
import com.intellij.idea.IdeaTestApplication
import com.intellij.java.navigation.ChooseByNameTest
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Computable
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightCodeInsightFixtureTestCase
import groovy.transform.CompileStatic
import org.jetbrains.annotations.NonNls
import org.jetbrains.annotations.NotNull

import java.util.concurrent.TimeUnit

/**
 * @author peter
 */
@CompileStatic
class GotoActionTest extends LightCodeInsightFixtureTestCase {
  private static final DataKey<Boolean> SHOW_HIDDEN_KEY = DataKey.create("GotoActionTest.DataKey")

  void "test shorter actions first despite ellipsis"() {
    def pattern = 'Rebas'
    def fork = 'Rebase my GitHub fork'
    def rebase = 'Rebase...'
    def items = [matchedAction(fork, pattern),
                 matchedAction(rebase, pattern)].sort()
    assert [rebase, fork] == items.collect { it.valueText }
  }

  void "test sort by match mode"() {
    def pattern = 'by'
    def byName = 'By Name'
    def byDesc = 'By Desc'
    def items = [matchedAction(byName, pattern),
                 matchedAction(byDesc, pattern, MatchMode.DESCRIPTION)].sort()
    assert [byName, byDesc] == items.collect { it.valueText }
  }

  void "test sort by degree"() {
    def pattern = 'c'
    def copy = 'Copy'
    def aardvark = 'Aardvarck'
    def boom = 'Boom'
    def deaf = 'deaf'
    def eclaire = 'eclaire'
    def cut = 'Cut'
    def c = 'c'
    def items = [matchedAction(boom, pattern),
                 matchedAction(aardvark, pattern),
                 matchedAction(copy, pattern),
                 matchedAction(eclaire, pattern),
                 matchedAction(deaf, pattern),
                 matchedAction(cut, pattern),
                 matchedAction(c, pattern)].sort()
    assert [c, copy, cut, aardvark, eclaire, boom, deaf] == items.collect { it.valueText }
  }

  void "test match action by parent and grandparent group name"() {
    def extractMethod = ActionManager.instance.getAction("ExtractMethod")
    assert actionMatches('method', extractMethod) == MatchMode.NAME
    assert actionMatches('extract method', extractMethod) == MatchMode.GROUP
    assert actionMatches('refactor method', extractMethod) == MatchMode.GROUP
  }

  void "test matched value comparator"() {
    def pattern = 'Text'
    def names = ['Text', 'Text completion', 'Completion Text', 'Add text', 'Retextovize mapping', 'Value', 'A', 'Z']

    def items = new ArrayList<MatchedValue>()
    names.forEach { String name ->
      items += matchedAction(name, pattern, MatchMode.NAME, true)
      items += matchedAction(name, pattern, MatchMode.NAME, false)
      items += matchedAction(name, pattern, MatchMode.DESCRIPTION, true)
      items += matchedAction(name, pattern, MatchMode.DESCRIPTION, false)
      items += matchedOption(name, pattern)
      items += matchedOption(name, pattern)
      items += matchedBooleanOption(name, pattern)
      items += new MatchedValue(name, pattern)
    }

    PlatformTestUtil.assertComparisonContractNotViolated(items,
                                                         { def item1, def item2 -> (item1 <=> item2) },
                                                         { def item1, def item2 -> item1 == item2 })

    // order can be different on EDT and pooled threads
    ApplicationManager.getApplication().executeOnPooledThread {
      PlatformTestUtil.assertComparisonContractNotViolated(items,
                                                           { def item1, def item2 -> (item1 <=> item2) },
                                                           { def item1, def item2 -> item1 == item2 })
    }.get(20000, TimeUnit.MILLISECONDS)
  }

  void "test same action is not reported twice"() {
    def patterns = ["Patch", "Add", "Delete", "Show", "Toggle"]

    def model = new GotoActionModel(project, null, null)
    def provider = new GotoActionItemProvider(model)

    def popup = ChooseByNamePopup.createPopup(project, model, provider)
    try {
      patterns.forEach { String pattern ->
        def result = ChooseByNameTest.calcPopupElements(popup, pattern, true)
        def actions = result.findResults {
          if (it instanceof MatchedValue) {
            def value = it.value
            if (value instanceof ActionWrapper) {
              return (value as ActionWrapper).action
            }
            if (value instanceof OptionDescription) {
              return value
            }
          }
          return null
        }
        assert actions.size() == actions.toSet().size()
      }
    }
    finally {
      popup.close(false)
    }
  }

  void "test detected action groups"() {
    assert getPresentableGroupName(project, "Zoom", "Images.Editor.ZoomIn", false) == "Images"
    assert getPresentableGroupName(project, "Tab", "SearchEverywhere.NextTab", false) == "Search Everywhere"
    assert getPresentableGroupName(project, "Tab", "NextTab", false) == "Window | Editor Tabs"
    assert getPresentableGroupName(project, "Next Tab", "NextEditorTab", false) == "Tabs"
  }

  void "test same invisible groups are ignored"() {
    def pattern = "GotoActionTest.TestAction"

    def testAction = createAction(pattern)
    def outerGroup = createActionGroup("Outer", false)
    def visibleGroup = createActionGroup("VisibleGroup", false)
    def hiddenGroup1 = createActionGroup("A HiddenGroup1", true)
    def hiddenGroup2 = createActionGroup("Z HiddenGroup2", true)
    outerGroup.add(hiddenGroup1)
    outerGroup.add(visibleGroup)
    outerGroup.add(hiddenGroup2)
    visibleGroup.add(testAction)
    hiddenGroup1.add(testAction)
    hiddenGroup2.add(testAction)

    runWithGlobalAction(pattern, testAction) {
      runWithMainMenuGroup(outerGroup) {
        assert getPresentableGroupName(project, pattern, testAction, false) == "Outer | VisibleGroup"
        assert getPresentableGroupName(project, pattern, testAction, true) == "Outer | A HiddenGroup1"

        outerGroup.remove(visibleGroup)

        assert getPresentableGroupName(project, pattern, testAction, false) == null
        assert getPresentableGroupName(project, pattern, testAction, true) == "Outer | A HiddenGroup1"

        outerGroup.remove(hiddenGroup1)

        assert getPresentableGroupName(project, pattern, testAction, false) == null
        assert getPresentableGroupName(project, pattern, testAction, true) == "Outer | Z HiddenGroup2"

        hiddenGroup2.remove(testAction)

        assert getPresentableGroupName(project, pattern, testAction, false) == null
        assert getPresentableGroupName(project, pattern, testAction, true) == null
      }
    }
  }

  void "test action order is stable with different presentation"() {
    def pattern = "GotoActionTest.TestAction"

    def testAction1 = createAction(pattern)
    def testAction2 = createAction(pattern)
    def outerGroup = createActionGroup("Outer", false)
    def hiddenGroup = createActionGroup("A Hidden", false)
    def visibleGroup1 = createActionGroup("K Visible", true)
    def visibleGroup2 = createActionGroup("Z Visible", true)
    outerGroup.add(hiddenGroup)
    outerGroup.add(visibleGroup1)
    outerGroup.add(visibleGroup2)
    visibleGroup1.add(testAction1)
    visibleGroup2.add(testAction2)
    hiddenGroup.add(testAction2)

    runWithGlobalAction(pattern + "1", testAction1) {
      runWithGlobalAction(pattern + "2", testAction2) {
        runWithMainMenuGroup(outerGroup) {
          def order1 = computeWithCustomDataProvider(true) {
            getSortedActionsFromPopup(project, pattern)
          }

          def order2 = computeWithCustomDataProvider(false) {
            getSortedActionsFromPopup(project, pattern)
          }

          assert order1 == order2
        }
      }
    }
  }

  private static List<ActionWrapper> getSortedActionsFromPopup(Project project, String pattern) {
    def wrappers = getActionsFromPopup(project, pattern)
    wrappers.every { it.getPresentation() } // update best group name
    wrappers.sort()
    return wrappers
  }

  private static String getPresentableGroupName(Project project, String pattern, String testActionId, boolean passFlag) {
    def action = ActionManager.instance.getAction(testActionId)
    assert action != null
    return getPresentableGroupName(project, pattern, action, passFlag)
  }

  private static String getPresentableGroupName(Project project, String pattern, AnAction testAction, boolean passFlag) {
    return computeWithCustomDataProvider(passFlag) {
      def result = getActionsFromPopup(project, pattern)
      def matches = result.findAll { it.action == testAction }
      assert matches.size() == 1

      ActionWrapper wrapper = matches[0]
      wrapper.getPresentation() // update before show
      return wrapper.groupName
    }
  }

  private static void runWithGlobalAction(String id, AnAction action, Runnable task) {
    ActionManager.instance.registerAction(id, action)
    try {
      task.run()
    }
    finally {
      ActionManager.instance.unregisterAction(id)
    }
  }

  private static void runWithMainMenuGroup(ActionGroup group, Runnable task) {
    def mainMenuGroup = (DefaultActionGroup)ActionManager.getInstance().getAction(IdeActions.GROUP_MAIN_MENU)
    mainMenuGroup.add(group)
    try {
      task.run()
    }
    finally {
      mainMenuGroup.remove(group)
    }
  }

  private static <T> T computeWithCustomDataProvider(passHiddenFlag, Computable<T> task) {
    IdeaTestApplication.getInstance().setDataProvider(new DataProvider() {
      @Override
      Object getData(@NotNull @NonNls String dataId) {
        if (SHOW_HIDDEN_KEY.is(dataId) && passHiddenFlag) return Boolean.TRUE
        return null
      }
    })

    try {
      return task.compute()
    }
    finally {
      IdeaTestApplication.getInstance().setDataProvider(null)
    }
  }

  private static List<ActionWrapper> getActionsFromPopup(Project project, String pattern) {
    def model = new GotoActionModel(project, null, null)
    def provider = new GotoActionItemProvider(model)
    def popup = ChooseByNamePopup.createPopup(project, model, provider)
    try {
      return ChooseByNameTest.calcPopupElements(popup, pattern, true).findResults {
        if (it instanceof MatchedValue && it.value instanceof ActionWrapper) {
          return it.value as ActionWrapper
        }
        return null
      } as List<ActionWrapper>
    }
    finally {
      popup.close(false)
    }
  }

  private def actionMatches(String pattern, AnAction action) {
    return new GotoActionModel(project, null, null).actionMatches(pattern, GotoActionItemProvider.buildMatcher(pattern), action)
  }

  private MatchedValue matchedAction(String text, String pattern, MatchMode mode = MatchMode.NAME, boolean isAvailable = true) {
    return matchedAction(createAction(text), pattern, mode, isAvailable)
  }

  private MatchedValue matchedAction(AnAction action, String pattern, MatchMode mode = MatchMode.NAME, boolean isAvailable = true) {
    def model = new GotoActionModel(project, null, null)
    def wrapper = new ActionWrapper(action, null, mode, DataContext.EMPTY_CONTEXT, model) {
      @Override
      boolean isAvailable() {
        return isAvailable
      }
    }
    new MatchedValue(wrapper, pattern)
  }

  private static AnAction createAction(String text) {
    new AnAction(text) {
      @Override
      void actionPerformed(@NotNull AnActionEvent e) {
      }
    }
  }

  private static DefaultActionGroup createActionGroup(String text, boolean hideByDefault) {
    new DefaultActionGroup(text, true) {
      @Override
      void update(@NotNull AnActionEvent e) {
        e.presentation.setVisible(!hideByDefault || Boolean.valueOf(e.getData(SHOW_HIDDEN_KEY)))
      }
    }
  }


  private static MatchedValue matchedOption(String text, String pattern) {
    return new MatchedValue(new OptionDescription(text), pattern)
  }

  private static MatchedValue matchedBooleanOption(String text, String pattern) {
    def option = new BooleanOptionDescription(text, text) {
      @Override
      boolean isOptionEnabled() {
        return false
      }

      @Override
      void setOptionState(boolean enabled) {
      }
    }
    return new MatchedValue(option, pattern)
  }
}
