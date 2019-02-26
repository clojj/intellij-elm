package org.elm.ide.toolwindow

import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.AutoScrollToSourceHandler
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentManager
import org.elm.openapiext.checkIsEventDispatchThread
import org.elm.openapiext.findFileByPath
import org.elm.utils.CircularList
import org.elm.workspace.compiler.*
import java.awt.GridLayout
import java.nio.file.Paths
import javax.swing.JComponent
import javax.swing.JTextPane
import javax.swing.ListSelectionModel


class ElmCompilerPanel(private val project: Project, private val contentManager: ContentManager) : SimpleToolWindowPanel(true, false) {

    private val actionIdForward: String = "Elm.MessageForward"
    private val actionIdBack: String = "Elm.MessageBack"

    private val errorListUI = JBList<String>(emptyList()).apply {
        emptyText.text = ""
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        object : AutoScrollToSourceHandler() {
            override fun isAutoScrollMode() = true
            override fun setAutoScrollMode(state: Boolean) {}
        }.install(this)
        addListSelectionListener {
            if (!it.valueIsAdjusting && selectedIndex >= 0) {
                compilerMessages.set(selectedIndex)
                messageUI.text = compilerMessages.get().messageWithRegion.message
            }
        }
    }

    private val messageUI = JTextPane().apply {
        contentType = "text/html"
        // background = Color(200, 200, 200) // TODO color themes
    }

    var compilerMessages: CircularList<CompilerMessage> = CircularList(emptyList())
        set(value) {
            checkIsEventDispatchThread()
            field = value
            if (compilerMessages.isEmpty()) {
                errorListUI.setListData(emptyArray())
                messageUI.text = "No Errors"
            } else {
                val titles = compilerMessages.list.map { it.path + prettyRegion(it.messageWithRegion.region) + "    " + it.messageWithRegion.title }
                errorListUI.setListData(titles.toTypedArray())
                messageUI.text = compilerMessages.get().messageWithRegion.message
            }
        }

    private fun prettyRegion(region: Region): String {
        return ": line ${region.start.line} column ${region.start.column}"
    }

    init {
        setToolbar(createToolbar())

        val jbPanel = JBPanel<JBPanel<*>>(GridLayout(1, 2))
        jbPanel.add(JBScrollPane(errorListUI))
        jbPanel.add(messageUI)
        setContent(jbPanel)

        with(project.messageBus.connect()) {
            subscribe(ElmBuildAction.ERRORS_TOPIC, object : ElmBuildAction.ElmErrorsListener {
                override fun update(messages: List<CompilerMessage>) {
                    ApplicationManager.getApplication().invokeLater {
                        compilerMessages = CircularList(messages)
                        contentManager.getContent(0)?.displayName = "${compilerMessages.list.size} errors"
                        errorListUI.selectedIndex = 0
                    }
                }
            })
            subscribe(ElmForwardAction.ERRORS_FORWARD_TOPIC, object : ElmForwardAction.ElmErrorsForwardListener {
                override fun forward() {
                    if (!compilerMessages.isEmpty())
                        ApplicationManager.getApplication().invokeLater {
                            messageUI.text = compilerMessages.next().messageWithRegion.message
                            errorListUI.selectedIndex = compilerMessages.getIndex()
                        }
                }
            })
            subscribe(ElmBackAction.ERRORS_BACK_TOPIC, object : ElmBackAction.ElmErrorsBackListener {
                override fun back() {
                    if (!compilerMessages.isEmpty())
                        ApplicationManager.getApplication().invokeLater {
                            messageUI.text = compilerMessages.prev().messageWithRegion.message
                            errorListUI.selectedIndex = compilerMessages.getIndex()
                        }
                }
            })
        }
    }


    private fun createToolbar(): JComponent {
        val compilerPanel = this
        val toolbar = with(ActionManager.getInstance()) {
            // TODO alternative management of actions ? test with closing the project, then reopen it!
            val defaultActionGroup = getAction("Elm.CompilerToolsGroup") as DefaultActionGroup
            val action = getAction(actionIdForward)
            if (action != null) {
                defaultActionGroup.remove(action)
                unregisterAction(actionIdForward)
                defaultActionGroup.remove(getAction(actionIdBack))
                unregisterAction(actionIdBack)
            }
            // TODO is this safe ?
            val elmBackAction = ElmBackAction(compilerPanel)
            registerAction(actionIdBack, elmBackAction)
            val elmForwardAction = ElmForwardAction(compilerPanel)
            registerAction(actionIdForward, elmForwardAction)

            defaultActionGroup.addSeparator()
            defaultActionGroup.add(elmBackAction)
            defaultActionGroup.add(elmForwardAction)

            createActionToolbar(
                    "Elm Compiler Toolbar", // the value here doesn't matter, as far as I can tell
                    getAction("Elm.CompilerToolsGroup") as DefaultActionGroup, // defined in plugin.xml
                    true // horizontal layout
            )
        }
        toolbar.setTargetComponent(this)
        return toolbar.component
    }

    override fun getData(dataId: String): Any? {
        return when {
            CommonDataKeys.NAVIGATABLE.`is`(dataId) -> {
                if (!compilerMessages.isEmpty()) {
                    val file = LocalFileSystem.getInstance().findFileByPath(Paths.get(project.basePath + "/" + compilerMessages.get().path))
                    val start = compilerMessages.get().messageWithRegion.region.start
                    OpenFileDescriptor(project, file!!, start.line - 1, start.column - 1)
                } else {
                    super.getData(dataId)
                }
            }
            else ->
                super.getData(dataId)
        }
    }
}
