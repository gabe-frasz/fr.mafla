package fr.mafla.helper

import fr.mafla.data.AppModel

interface AppFilterHelper {
    fun onAppFiltered(items:List<AppModel>)
}