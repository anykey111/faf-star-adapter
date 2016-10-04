#pragma once

#include "HttpServer.h"
#include "HttpClient.h"
#include "IceAgent.h"

typedef struct _GMainLoop  GMainLoop;
class IceStream;

class IceAdapter
{
public:
  IceAdapter(std::string const& playerId,
             std::string const& httpBaseUri,
             std::string const& stunHost,
             std::string const& turnHost,
             std::string const& turnUser,
             std::string const& turnPassword,
             unsigned int httpPort);
  virtual ~IceAdapter();
  void run();

protected:
  void onJoinGame(std::string const& gameId);
  void onCreateJoinGame(std::string const& gameId);
  void onSdp(IceStream* stream, std::string const& sdp);
  void onReceive(IceStream* stream, std::string const& msg);
  void onConnectPlayers();
  void onPingPlayers();
  void onSetPlayerId(std::string const& playerId);
  std::string onStatus();

  void parseGameInfoAndConnectPlayers(std::string const& jsonGameInfo);

  GMainLoop*   mMainLoop;
  IceAgent     mIceAgent;
  HttpServer   mHttpServer;
  HttpClient   mHttpClient;
  std::string  mPlayerId;
  std::map<IceStream*, std::string> mStreamRemoteplayerMap;
  std::map<IceStream*, unsigned long long> mSentPings;
  std::map<IceStream*, unsigned long long> mReceivedPings;
  std::map<IceStream*, unsigned long long> mReceivedPongs;
  std::map<std::string, IceStream*> mRemoteplayerStreamMap;
  std::string  mGameId;
  std::string  mTurnHost;
  std::string  mTurnUser;
  std::string  mTurnPassword;
  unsigned int mConnectPlayersTimer;
  unsigned int mPingPlayersTimer;

  friend int connect_players_timeout(void*);
  friend int ping_players_timeout(void*);
};
