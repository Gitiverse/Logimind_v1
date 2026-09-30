package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.example.data.local.AppDatabase
import com.example.data.model.FormTemplate
import com.example.data.repository.FormRepository
import com.example.ui.screens.FormSolveScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.TemplateDesignerScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.FormBuilderViewModel
import com.example.ui.viewmodel.FormBuilderViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Initialize Room Local Encryption-Ready Databases
        val database = AppDatabase.getDatabase(this)
        val repository = FormRepository(database.medicalFormDao())
        
        // 2. Instantiate state ViewModel via Factory
        val factory = FormBuilderViewModelFactory(repository, this)
        val viewModel = ViewModelProvider(this, factory)[FormBuilderViewModel::class.java]

        setContent {
            MyApplicationTheme {
                // Navigation State: 0 = Home, 1 = Template Designer, 2 = Intake Solver
                var currentScreenIndex by remember { mutableIntStateOf(0) }

                Scaffold(
                    modifier = Modifier.fillMaxSize()
                ) { innerPadding ->
                    Box(modifier = Modifier.padding(innerPadding)) {
                        when (currentScreenIndex) {
                            0 -> {
                                HomeScreen(
                                    viewModel = viewModel,
                                    onNavigateToDesigner = { template ->
                                        viewModel.selectTemplateForEdit(template)
                                        currentScreenIndex = 1
                                    },
                                    onNavigateToSolve = { template ->
                                        viewModel.selectTemplateForEdit(template)
                                        currentScreenIndex = 2
                                    }
                                )
                            }
                            1 -> {
                                TemplateDesignerScreen(
                                    viewModel = viewModel,
                                    onNavigateBack = {
                                        currentScreenIndex = 0
                                    }
                                )
                            }
                            2 -> {
                                FormSolveScreen(
                                    viewModel = viewModel,
                                    onNavigateBack = {
                                        currentScreenIndex = 0
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
