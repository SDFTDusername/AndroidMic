
use app::run_ui;
use byteordered::{
    byteorder::{BigEndian, LittleEndian},
    Endianness,
};
use clap::Parser;
use cpal::traits::StreamTrait;
use local_ip_address::local_ip;
use rtrb::RingBuffer;
use streamer::Streamer;

use user_action::UserAction;

use std::sync::mpsc;

use crate::{
    audio::setup_audio, streamer::WriteError, tcp_streamer::TcpStreamer, udp_streamer::UdpStreamer,
    user_action::Args,
};


struct App {
    audio_player: Option<cpal::Stream>,
    streamer: Option<Box<dyn Streamer>>,
}

impl App {
    fn new() -> Self {
        App {
            audio_player: None,
            streamer: None,
        }
    }
}

const SHARED_BUF_SIZE: usize = 5 * 1024;

fn main2() {
    env_logger::init();

    let args = Args::parse();

    let mut app = App::new();

    let (producer, consumer) = RingBuffer::<u8>::new(SHARED_BUF_SIZE);

    info!("{:?}", Endianness::native());

    let audio_stream_builder = match Endianness::native() {
        Endianness::Little => setup_audio::<LittleEndian>(consumer, &args),
        Endianness::Big => {
            warn!("warning! most phone use little endian nowdays. we might need to convert little -> big");
            setup_audio::<BigEndian>(consumer, &args)
        }
    };
    match audio_stream_builder {
        Err(e) => {
            error!("{:?}", e);
            return;
        }
        Ok(steam) => match steam.play() {
            Ok(_) => app.audio_player = Some(steam),
            Err(e) => {
                error!("{:?}", e);
                return;
            }
        },
    }

    let ip = if let Some(ip) = args.ip {
        ip
    } else {
        local_ip().unwrap()
    };

    match args.connection_mode {
        user_action::ConnectionMode::Udp => {
            let streamer = UdpStreamer::new(producer, ip).unwrap();
            app.streamer = Some(Box::new(streamer))
        }
        user_action::ConnectionMode::Tcp => {
            let streamer = TcpStreamer::new(producer, ip).unwrap();
            app.streamer = Some(Box::new(streamer))
        }
    }

    let (tx, rx) = mpsc::channel::<UserAction>();
    user_action::start_listening(tx);
    user_action::print_avaible_action();

    let mut iteration: f64 = 0.0;
    let mut item_lossed: f64 = 0.0;
    let mut item_moved: f64 = 0.0;

    use std::time::Instant;
    let now = Instant::now();
    loop {
        if let Ok(action) = rx.try_recv() {
            match action {
                UserAction::Quit => {
                    println!("quit requested");
                    break;
                }
            }
        }

        let Some(streamer) = &mut app.streamer else {
            return;
        };

        match streamer.process() {
            Ok(moved) => item_moved += moved as f64,
            Err(e) => match e {
                WriteError::Io(e) => {
                    error!("Io Error: {:?}", e);
                    break;
                }
                WriteError::BufferOverfilled(moved, lossed) => {
                    item_lossed += lossed as f64;
                    item_moved += moved as f64;
                }
            },
        }
        iteration += 1.0;
    }

    println!();
    println!("Stats:");
    println!("elapsed: {:.2?}", now.elapsed());
    println!(
        "iteration: {}, item moved: {}, item lossed: {}",
        iteration, item_moved, item_lossed
    );
    println!("moved by iteration: {}", item_moved / iteration);
    println!("lossed by iteration: {}", item_lossed / iteration);
    println!(
        "success: {}%",
        (item_moved / (item_lossed + item_moved)) * 100.0
    )
}