package com.github.libretube.ui.fragments

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.libretube.api.RetrofitInstance
import com.github.libretube.api.obj.ChannelTab
import com.github.libretube.api.obj.StreamItem
import com.github.libretube.databinding.FragmentChannelContentBinding
import com.github.libretube.extensions.ceilHalf
import com.github.libretube.ui.adapters.SearchChannelAdapter
import com.github.libretube.ui.adapters.VideosAdapter
import com.github.libretube.ui.base.DynamicLayoutManagerFragment
import com.github.libretube.util.deArrow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class ChannelContentFragment : DynamicLayoutManagerFragment() {
    private var _binding: FragmentChannelContentBinding? = null
    private val binding get() = _binding!!
    private var channelId: String? = null
    private var searchChannelAdapter: SearchChannelAdapter? = null
    private var channelAdapter: VideosAdapter? = null
    private var recyclerViewState: Parcelable? = null
    private var nextPage: String? = null
    private var isLoading: Boolean = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChannelContentBinding.inflate(inflater, container, false)
        return _binding!!.root
    }

    override fun setLayoutManagers(gridItems: Int) {
        binding.channelRecView.layoutManager = GridLayoutManager(
            requireContext(),
            gridItems.ceilHalf()
        )
    }

    private suspend fun fetchChannelNextPage(nextPage: String): String? {
        val response = withContext(Dispatchers.IO) {
            RetrofitInstance.api.getChannelNextPage(channelId!!, nextPage).apply {
                relatedStreams = relatedStreams.deArrow()
            }
        }
        channelAdapter?.insertItems(response.relatedStreams)
        return response.nextpage
    }

    private suspend fun fetchTabNextPage(nextPage: String, tab: ChannelTab): String? {
        val newContent = withContext(Dispatchers.IO) {
            RetrofitInstance.api.getChannelTab(tab.data, nextPage)
        }.apply {
            content = content.deArrow()
        }

        searchChannelAdapter?.let {
            it.submitList(it.currentList + newContent.content)
        }
        isLoading = false
        return newContent.nextpage
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // manually restore the recyclerview state due to https://github.com/material-components/material-components-android/issues/3473
        binding.channelRecView.layoutManager?.onRestoreInstanceState(recyclerViewState)
    }

    private fun loadChannelTab(tab: ChannelTab) = lifecycleScope.launch {
        Log.e("Dataaa Tab in  fragment", tab.data)

        val response = try {
            withContext(Dispatchers.IO) {
                RetrofitInstance.api.getChannelTab(tab.data)
            }.apply {
                content = content.deArrow()
            }
        } catch (e: Exception) {
            binding.progressBar.visibility = View.GONE
            return@launch
        }
        withContext(Dispatchers.Main) {
            nextPage = response.nextpage
        }
        val binding = _binding ?: return@launch
        searchChannelAdapter = SearchChannelAdapter()
        binding.channelRecView.adapter = searchChannelAdapter

        searchChannelAdapter?.submitList(response.content)
        binding.progressBar.visibility = View.GONE
        binding.channelRecView.visibility = View.VISIBLE
        isLoading = false
    }

    private fun loadNextPage(isVideo: Boolean, tab: ChannelTab) = lifecycleScope.launch {
        try {
            isLoading = true
            if (isVideo) {
                fetchChannelNextPage(nextPage ?: return@launch).let {
                    nextPage = it
                }
            } else {
                fetchTabNextPage(nextPage ?: return@launch, tab).let {
                    nextPage = it
                }
            }

        } catch (e: Exception) {
            Log.e("error fetching tabs", e.toString())
            isLoading = false
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val tabData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelable("data", ChannelTab::class.java)
        } else {
            arguments?.getParcelable<ChannelTab>("data")
        }
        binding.channelRecView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                super.onScrollStateChanged(recyclerView, newState)
                recyclerViewState = binding.channelRecView.layoutManager?.onSaveInstanceState()
            }

            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                super.onScrolled(recyclerView, dx, dy)
                val visibleItemCount: Int = recyclerView.layoutManager!!.childCount
                val totalItemCount: Int = recyclerView.layoutManager!!.getItemCount()
                val firstVisibleItemPosition: Int =
                    (recyclerView.layoutManager as LinearLayoutManager).findFirstVisibleItemPosition()
                if (_binding == null || isLoading) return
                if (firstVisibleItemPosition + visibleItemCount >= totalItemCount) {
                    binding.paginationPb.visibility = View.VISIBLE
                    loadNextPage(tabData?.data!!.isEmpty(), tabData)
                    isLoading = false
                    binding.paginationPb.visibility = View.GONE
                }

            }
        })
        arguments?.takeIf { it.containsKey("data") }?.apply {
            var data: ChannelTab? = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                data = getParcelable("data", ChannelTab::class.java)
                binding.tv.text = data.toString()

            } else {
                data = getParcelable<ChannelTab>("data")
                binding.tv.text = data.toString()
            }
            if (!data?.data.isNullOrEmpty()) {
                loadChannelTab(data ?: ChannelTab("", ""))

            } else {
                val videoDataString = getString("videos_list")
                val videos = Json.decodeFromString<List<StreamItem>>(videoDataString!!)
                channelAdapter = VideosAdapter(
                    videos.toMutableList(),
                    forceMode = VideosAdapter.Companion.LayoutMode.CHANNEL_ROW
                )
                binding.channelRecView.adapter = channelAdapter
                binding.progressBar.visibility = View.GONE
                binding.channelRecView.visibility = View.VISIBLE
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}